import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Test data, monitoring and invariants for an isolated HTTP application instance. */
public class HttpCapacitySupport {
    static final ObjectMapper JSON = new ObjectMapper();
    final Map<String,String> env = new HashMap<>();
    final Path root;
    final Map<String,Object> session;
    HttpCapacitySupport(String envFile, String directory) throws Exception {
        root=Paths.get(directory); Files.createDirectories(root);
        for(String line:Files.readAllLines(Paths.get(envFile),StandardCharsets.UTF_8)) {
            line=line.trim(); if(line.isEmpty()||line.startsWith("#")) continue;
            int eq=line.indexOf('='); if(eq<1) continue;
            String v=line.substring(eq+1).trim();
            if(v.length()>1&&((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'"))))v=v.substring(1,v.length()-1);
            env.put(line.substring(0,eq).trim(),v);
        }
        Path p=root.resolve("session.json");
        session=Files.exists(p)?JSON.readValue(p.toFile(),LinkedHashMap.class):new LinkedHashMap<>();
    }
    String val(String k){return String.valueOf(session.get(k));}
    void save()throws Exception{JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("session.json").toFile(),session);}
    void write(Path p,Object v)throws Exception{JSON.writerWithDefaultPrettyPrinter().writeValue(p.toFile(),v);}
    String url(boolean isolated){String u=env.get("HMDP_DATASOURCE_URL");return isolated?u.replaceFirst("(?<=/)[^/?]+(?=\\?|$)",val("schema")):u;}
    java.sql.Connection db(boolean isolated)throws Exception{
        Properties p=new Properties();p.setProperty("user",env.get("HMDP_DATASOURCE_USERNAME"));p.setProperty("password",env.get("HMDP_DATASOURCE_PASSWORD"));
        p.setProperty("connectTimeout","5000");p.setProperty("socketTimeout","30000");p.setProperty("rewriteBatchedStatements","true");
        return DriverManager.getConnection(url(isolated),p);
    }
    Redis redis(int database)throws Exception{return new Redis(env.get("HMDP_REDIS_HOST"),Integer.parseInt(env.getOrDefault("HMDP_REDIS_PORT","6379")),env.get("HMDP_REDIS_PASSWORD"),database);}
    Redis redis()throws Exception{return redis(((Number)session.get("redisDb")).intValue());}
    static String str(Object v){return v instanceof byte[]?new String((byte[])v,StandardCharsets.UTF_8):String.valueOf(v);}
    static long number(Object v){return v==null?0:Long.parseLong(str(v));}
    static Map<String,String> fields(Object result){List<?> a=(List<?>)result;Map<String,String> m=new HashMap<>();for(int i=0;i<a.size();i+=2)m.put(str(a.get(i)),str(a.get(i+1)));return m;}
    static String key(String type,long voucher){return "seckill:"+type+":{"+voucher+"}";}
    long scalar(java.sql.Connection c,String q)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(q)){r.next();return r.getLong(1);}}
    void inspect()throws Exception{
        try(java.sql.Connection c=db(false);Redis r=redis(0)){
            session.put("sourceSchema",c.getCatalog());session.put("mysqlVersion",c.getMetaData().getDatabaseProductVersion());
            session.put("sourceUsers",scalar(c,"SELECT COUNT(*) FROM tb_user"));
            String run="r"+new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date())+"_"+UUID.randomUUID().toString().substring(0,8);
            session.put("runId",run);session.put("schema","hmdp_http_"+run);session.put("vhost","hmdp_http_"+run);session.put("rabbitUser",env.get("HMDP_RABBITMQ_USERNAME"));
            session.put("redisHost",env.get("HMDP_REDIS_HOST"));session.put("redisPort",env.get("HMDP_REDIS_PORT"));
            int free=-1;for(int i=15;i>=1;i--){try{r.command("SELECT",String.valueOf(i));if(number(r.command("DBSIZE"))==0){free=i;break;}}catch(Exception ignored){}}
            if(free<0)throw new IllegalStateException("No empty Redis logical database for isolation");
            session.put("redisDb",free);session.put("redisWasEmpty",true);session.put("schemaOwned",false);save();
            System.out.println("INSPECT "+JSON.writeValueAsString(session));
        }
    }
    void prepare(String usersFile)throws Exception{
        if(!val("schema").matches("hmdp_http_r[0-9]+_[a-f0-9]{8}")||Boolean.TRUE.equals(session.get("schemaOwned")))throw new IllegalStateException("Invalid/new schema required");
        try(java.sql.Connection c=db(false);Statement s=c.createStatement()){
            List<String> tables=new ArrayList<>();try(ResultSet r=s.executeQuery("SHOW TABLES")){while(r.next())tables.add(r.getString(1));}
            s.execute("CREATE DATABASE `"+val("schema")+"`");session.put("schemaOwned",true);save();
            for(String t:tables){if(!t.matches("[a-zA-Z0-9_]+"))throw new IllegalStateException("Invalid table name");s.execute("CREATE TABLE `"+val("schema")+"`.`"+t+"` LIKE `"+val("sourceSchema")+"`.`"+t+"`");}
            for(String t:new String[]{"tb_shop","tb_shop_type"})s.executeUpdate("INSERT INTO `"+val("schema")+"`.`"+t+"` SELECT * FROM `"+val("sourceSchema")+"`.`"+t+"`");
        }
        int total=0;
        try(java.sql.Connection c=db(true);BufferedReader in=Files.newBufferedReader(Paths.get(usersFile),StandardCharsets.UTF_8)){
            c.setAutoCommit(false);String header=in.readLine();if(!"\"phone\",\"nickName\",\"icon\"".equals(header))throw new IllegalStateException("Unexpected CSV header");
            try(PreparedStatement p=c.prepareStatement("INSERT INTO tb_user(phone,nick_name,icon) VALUES(?,?,?)")){
                String line;while((line=in.readLine())!=null){String[] a=line.split(",",-1);if(a.length!=3)throw new IllegalStateException("Unexpected CSV row");
                    for(int i=0;i<3;i++)p.setString(i+1,a[i].substring(1,a[i].length()-1));p.addBatch();total++;
                    if(total%1000==0){p.executeBatch();c.commit();if(total%20000==0)System.out.println("USERS "+total);}
                }p.executeBatch();c.commit();
            }
        }
        if(total!=100000)throw new IllegalStateException("Expected 100000 users, got "+total);
        int tokens=0;try(java.sql.Connection c=db(true);Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT id,nick_name,icon FROM tb_user ORDER BY id");Redis r=redis();BufferedWriter out=Files.newBufferedWriter(root.resolve("tokens.csv"),StandardCharsets.UTF_8)){
            while(rows.next()){
                String token=UUID.randomUUID().toString().replace("-","");String id=rows.getString(1);
                r.send("HSET","login:token:"+token,"id",id,"nickName",rows.getString(2),"icon",rows.getString(3));r.send("EXPIRE","login:token:"+token,"14400");
                out.write(token+","+id);out.newLine();tokens++;
                if(tokens%500==0){r.flush();for(int i=0;i<1000;i++)r.receive();}
            }
            if(tokens%500!=0){r.flush();for(int i=0;i<(tokens%500)*2;i++)r.receive();}
        }
        session.put("users",total);session.put("tokens",tokens);session.put("isolatedUrl",url(true));save();System.out.println("PREPARED users="+total+" tokens="+tokens+" redisDb="+val("redisDb"));
    }
    void voucher(Path stage,int stock)throws Exception{
        Files.createDirectories(stage);Map<String,Object> m=JSON.readValue(stage.resolve("stage.json").toFile(),LinkedHashMap.class);
        long id=System.currentTimeMillis();
        try(java.sql.Connection c=db(true);Statement s=c.createStatement();Redis r=redis()){
            c.setAutoCommit(false);
            s.executeUpdate("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES("+id+",1,'HTTP capacity "+val("runId")+"',100,200,1,1)");
            s.executeUpdate("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES("+id+","+stock+",CURRENT_TIMESTAMP-INTERVAL 1 DAY,CURRENT_TIMESTAMP+INTERVAL 1 DAY)");c.commit();
            List<?> time=(List<?>)r.command("TIME");long now=number(time.get(0))*1000;
            r.command("SET",key("stock",id),String.valueOf(stock));r.command("HSET",key("time",id),"beginTimeMillis",String.valueOf(now-86400000),"endTimeMillis",String.valueOf(now+86400000));
        }
        m.put("voucherId",id);m.put("initialStock",stock);write(stage.resolve("stage.json"),m);System.out.println("VOUCHER "+id+" stock="+stock);
    }
    com.rabbitmq.client.Connection rabbit()throws Exception{ConnectionFactory f=new ConnectionFactory();f.setHost(env.getOrDefault("HMDP_RABBITMQ_HOST","127.0.0.1"));f.setPort(Integer.parseInt(env.getOrDefault("HMDP_RABBITMQ_PORT","5672")));f.setUsername(env.get("HMDP_RABBITMQ_USERNAME"));f.setPassword(env.get("HMDP_RABBITMQ_PASSWORD"));f.setVirtualHost(val("vhost"));f.setAutomaticRecoveryEnabled(false);return f.newConnection("http-capacity-observer");}
    void monitor(Path stage)throws Exception{
        Map<String,Object> m=JSON.readValue(stage.resolve("stage.json").toFile(),LinkedHashMap.class);long id=number(m.get("voucherId"));int initial=((Number)m.get("initialStock")).intValue();
        try(java.sql.Connection c=db(true);Redis r=redis();com.rabbitmq.client.Connection mq=rabbit();Channel ch=mq.createChannel();BufferedWriter out=Files.newBufferedWriter(stage.resolve("monitor.csv"),StandardCharsets.UTF_8)){
            out.write("timestamp,orders,queueReady,reservations,pending,failed,redisMemory,redisCpuSeconds,rowLockWaits,rowLockMs,threadsRunning,consumers,redisCommands,redisOpsPerSec\n");
            while(!Files.exists(stage.resolve("stop-monitor"))){
                long now=System.currentTimeMillis(),orders=initial-scalar(c,"SELECT stock FROM tb_seckill_voucher WHERE voucher_id="+id);
                com.rabbitmq.client.AMQP.Queue.DeclareOk queue=ch.queueDeclarePassive("hmdp.order.create.queue");long ready=queue.getMessageCount();
                Map<String,String> info=info(r);long memory=Long.parseLong(info.getOrDefault("used_memory","0"));
                Map<String,Long> sql=new HashMap<>();try(Statement s=c.createStatement();ResultSet z=s.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running')")){while(z.next())sql.put(z.getString(1),z.getLong(2));}
                long reserved=number(r.command("HLEN",key("reservation",id))),pending=number(r.command("ZCARD",key("pending",id))),failed=number(r.command("ZCARD",key("pending:failed",id)));
                double cpu=Double.parseDouble(info.getOrDefault("used_cpu_sys","0"))+Double.parseDouble(info.getOrDefault("used_cpu_user","0"));
                out.write(now+","+orders+","+ready+","+reserved+","+pending+","+failed+","+memory+","+cpu+","+sql.getOrDefault("Innodb_row_lock_waits",0L)+","+sql.getOrDefault("Innodb_row_lock_time",0L)+","+sql.getOrDefault("Threads_running",0L)+","+queue.getConsumerCount()+","+info.getOrDefault("total_commands_processed","0")+","+info.getOrDefault("instantaneous_ops_per_sec","0")+"\n");out.flush();
                if(ready>20000||memory>1536L*1024*1024){Files.write(stage.resolve("stop-requests"),Collections.singletonList("observer safety threshold"),StandardCharsets.UTF_8);}
                Thread.sleep(1000);
            }
        }
    }
    Map<String,String> info(Redis r)throws Exception{Map<String,String> m=new HashMap<>();for(String l:str(r.command("INFO")).split("\\r?\\n")){int e=l.indexOf(':');if(e>0)m.put(l.substring(0,e),l.substring(e+1));}return m;}
    void validate(Path stage)throws Exception{
        Map<String,Object> m=JSON.readValue(stage.resolve("stage.json").toFile(),LinkedHashMap.class);long id=number(m.get("voucherId")),initial=number(m.get("initialStock"));
        try(java.sql.Connection c=db(true);Redis r=redis();com.rabbitmq.client.Connection mq=rabbit();Channel ch=mq.createChannel()){
            long until=System.currentTimeMillis()+180000;long rows=0,remaining=0,reserved=0,ready=0,pend=0,failed=0;
            do{remaining=scalar(c,"SELECT stock FROM tb_seckill_voucher WHERE voucher_id="+id);rows=initial-remaining;reserved=number(r.command("HLEN",key("reservation",id)));ready=ch.queueDeclarePassive("hmdp.order.create.queue").getMessageCount();pend=number(r.command("ZCARD",key("pending",id)));failed=number(r.command("ZCARD",key("pending:failed",id)));if(rows==reserved&&ready==0&&pend==0)break;Thread.sleep(1000);}while(System.currentTimeMillis()<until);
            long actual=scalar(c,"SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id="+id);
            long duplicates=scalar(c,"SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id="+id+" GROUP BY user_id HAVING COUNT(*)>1) t");
            long redisStock=number(r.command("GET",key("stock",id)));Map<String,Object> v=new LinkedHashMap<>();
            v.put("orders",actual);v.put("mysqlStock",remaining);v.put("redisStock",redisStock);v.put("reservations",reserved);v.put("queueReady",ready);v.put("pending",pend);v.put("failed",failed);v.put("duplicateUsers",duplicates);
            v.put("invariantsValid",actual==initial-remaining&&actual==reserved&&redisStock==remaining&&remaining>=0&&duplicates==0&&pend==0&&failed==0&&ready==0);
            try(Statement s=c.createStatement();ResultSet ids=s.executeQuery("SELECT id,user_id FROM tb_voucher_order WHERE voucher_id="+id);BufferedWriter out=Files.newBufferedWriter(stage.resolve("orders.csv"),StandardCharsets.UTF_8)){
                out.write("orderId,userId,acceptedAt,consumeStartedAt,dbCommittedAt,serverE2eMs,queueDelayMs,dbProcessMs,status\n");
                List<String[]> batch=new ArrayList<>();int completed=0;
                while(ids.next()){String order=ids.getString(1),user=ids.getString(2);batch.add(new String[]{order,user});r.send("HGETALL","seckill:pending:data:{"+id+"}:"+order);if(batch.size()==200){completed+=dumpOrders(r,batch,out);batch.clear();}}
                if(!batch.isEmpty())completed+=dumpOrders(r,batch,out);v.put("completionMetadataRows",completed);
            }
            write(stage.resolve("validation.json"),v);System.out.println("VALIDATION "+JSON.writeValueAsString(v));
        }
    }
    int dumpOrders(Redis r,List<String[]> ids,BufferedWriter out)throws Exception{r.flush();int done=0;for(String[] id:ids){Map<String,String> d=fields(r.receive());if("ORDER_CREATED".equals(d.get("status")))done++;out.write(id[0]+","+id[1]+","+d.getOrDefault("createTime","")+","+d.getOrDefault("consumeStartTime","")+","+d.getOrDefault("dbCommittedTime","")+","+d.getOrDefault("endToEndMillis","")+","+d.getOrDefault("queueDelayMillis","")+","+d.getOrDefault("dbProcessMillis","")+","+d.getOrDefault("status","")+"\n");}return done;}
    void cleanup()throws Exception{
        if(!Boolean.TRUE.equals(session.get("schemaOwned"))||!Boolean.TRUE.equals(session.get("redisWasEmpty"))||!val("schema").matches("hmdp_http_r[0-9]+_[a-f0-9]{8}")||!val("schema").equals("hmdp_http_"+val("runId"))||!val("schema").equals(val("vhost"))||number(session.get("redisDb"))<=0)throw new IllegalStateException("Ownership verification failed");
        try(java.sql.Connection c=db(false);Statement s=c.createStatement();Redis r=redis()){
            List<String> tableNames=new ArrayList<>();
            try(ResultSet tables=s.executeQuery("SHOW TABLES FROM `"+val("schema")+"`")){while(tables.next())tableNames.add(tables.getString(1));}
            try(BufferedWriter ddl=Files.newBufferedWriter(root.resolve("schema.sql"),StandardCharsets.UTF_8)){
                ddl.write("-- Structure of the isolated test schema; synthetic rows are stored separately.\n");
                for(String table:tableNames){
                    if(!table.matches("[a-zA-Z0-9_]+"))throw new IllegalStateException("Unexpected table identifier");
                    try(ResultSet create=s.executeQuery("SHOW CREATE TABLE `"+val("schema")+"`.`"+table+"`")){
                        create.next();ddl.write(create.getString(2).replaceAll("AUTO_INCREMENT=[0-9]+", "AUTO_INCREMENT=1"));ddl.write(";\n\n");
                    }
                }
            }
            for(int pass=0;pass<2;pass++){String cursor="0";do{List<?> result=(List<?>)r.command("SCAN",cursor,"COUNT","500");cursor=str(result.get(0));List<?> keys=(List<?>)result.get(1);if(!keys.isEmpty()){String[] cmd=new String[keys.size()+1];cmd[0]="UNLINK";for(int i=0;i<keys.size();i++)cmd[i+1]=str(keys.get(i));r.command(cmd);}}while(!"0".equals(cursor));}
            long remaining=number(r.command("DBSIZE"));if(remaining!=0)throw new IllegalStateException("Redis test database still has keys: "+remaining);
            s.execute("DROP DATABASE `"+val("schema")+"`");session.put("schemaOwned",false);session.put("cleaned",true);save();System.out.println("CLEANUP_OK redisKeys="+remaining);
        }
    }
    void verifyCleanup()throws Exception{
        Map<String,Object> result=new LinkedHashMap<>();
        try(java.sql.Connection c=db(false);Redis r=redis()){
            result.put("sourceUsersBefore",session.get("sourceUsers"));
            result.put("sourceUsersAfter",scalar(c,"SELECT COUNT(*) FROM tb_user"));
            result.put("testSchemaRemaining",scalar(c,"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+val("schema")+"'"));
            result.put("testRedisKeysRemaining",number(r.command("DBSIZE")));
            result.put("schemaCleaned",Boolean.TRUE.equals(session.get("cleaned")));
        }
        write(root.resolve("cleanup-validation.json"),result);System.out.println("CLEANUP_VERIFIED "+JSON.writeValueAsString(result));
    }
    public static void main(String[] a)throws Exception{
        HttpCapacitySupport h=new HttpCapacitySupport(a[1],a[2]);try{
            switch(a[0]){case "inspect":h.inspect();break;case "prepare":h.prepare(a[3]);break;case "voucher":h.voucher(Paths.get(a[3]),Integer.parseInt(a[4]));break;case "monitor":h.monitor(Paths.get(a[3]));break;case "validate":h.validate(Paths.get(a[3]));break;case "cleanup":h.cleanup();break;case "verify-cleanup":h.verifyCleanup();break;default:throw new IllegalArgumentException("Unknown action");}
        }catch(Exception e){String s=e.toString();for(String k:Arrays.asList("HMDP_DATASOURCE_PASSWORD","HMDP_REDIS_PASSWORD","HMDP_RABBITMQ_PASSWORD")){String secret=h.env.get(k);if(secret!=null&&!secret.isEmpty())s=s.replace(secret,"[redacted]");}System.err.println(s);System.exit(1);}
    }
    static class Redis implements AutoCloseable{
        final Socket socket;final BufferedInputStream in;final BufferedOutputStream out;
        Redis(String host,int port,String pass,int database)throws Exception{socket=new Socket();socket.connect(new InetSocketAddress(host,port),5000);socket.setSoTimeout(20000);in=new BufferedInputStream(socket.getInputStream(),65536);out=new BufferedOutputStream(socket.getOutputStream(),65536);if(pass!=null&&!pass.isEmpty())command("AUTH",pass);command("SELECT",String.valueOf(database));}
        void send(String...args)throws Exception{out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.UTF_8));for(String a:args){byte[] b=a.getBytes(StandardCharsets.UTF_8);out.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.UTF_8));out.write(b);out.write('\r');out.write('\n');}}
        void flush()throws Exception{out.flush();}Object command(String...a)throws Exception{send(a);flush();return receive();}
        String line()throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();int ch;while((ch=in.read())!=-1){if(ch=='\r'){if(in.read()!='\n')throw new IOException("RESP newline");return b.toString("UTF-8");}b.write(ch);}throw new EOFException();}
        Object receive()throws Exception{int type=in.read();if(type<0)throw new EOFException();String l=line();switch(type){case '+':return l;case '-':throw new IOException(l);case ':':return Long.valueOf(l);case '$':int n=Integer.parseInt(l);if(n<0)return null;byte[] b=new byte[n];for(int pos=0;pos<n;){int got=in.read(b,pos,n-pos);if(got<0)throw new EOFException();pos+=got;}if(in.read()!='\r'||in.read()!='\n')throw new IOException("RESP ending");return b;case '*':int size=Integer.parseInt(l);List<Object> a=new ArrayList<>();for(int i=0;i<size;i++)a.add(receive());return a;default:throw new IOException("RESP type "+type);}}
        public void close()throws Exception{socket.close();}
    }
}
