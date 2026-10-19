package capacity;

import com.rabbitmq.client.*;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated component benchmarks. Never changes application tables, keys, or queues. */
public class CapacitySampler extends AbstractJavaSamplerClient {
    private static State state;
    private String phase;
    private Throwable setupFailure;
    private Redis redis;
    private java.sql.Connection db;
    private PreparedStatement read, insert, update;
    private com.rabbitmq.client.Connection rabbit;
    private Channel publisher;
    private long sequence;
    private int worker;
    private final byte[] payload = new byte[1024];

    public Arguments getDefaultParameters() {
        Arguments a = new Arguments();
        for (String name : new String[]{"phase", "scenario", "envFile", "runId", "resultDir", "threads", "batch", "luaFile"}) a.addArgument(name, "");
        return a;
    }

    public void setupTest(JavaSamplerContext c) {
        phase = c.getParameter("phase");
        if (!"measure".equals(phase)) return;
        try {
            if (state == null) throw new IllegalStateException("Prepare phase did not complete");
            worker = state.nextWorker.incrementAndGet();
            Arrays.fill(payload, (byte)'x');
            if (state.scenario.startsWith("redis")) redis = state.redis();
            else if (state.scenario.startsWith("mysql")) {
                db = state.database();
                if (state.scenario.startsWith("mysql-read")) {
                    read = db.prepareStatement("SELECT payload FROM `" + state.schema + "`.reads WHERE id=?");
                } else {
                    db.setAutoCommit(false);
                    insert = db.prepareStatement("INSERT INTO `" + state.schema + "`.orders (id,user_id,voucher_id) VALUES (?,?,?)");
                    update = db.prepareStatement("UPDATE `" + state.schema + "`.stocks SET stock=stock-1 WHERE voucher_id=? AND stock>0");
                }
            } else {
                rabbit = state.rabbitFactory().newConnection("capacity-publisher-" + worker);
                publisher = rabbit.createChannel();
                publisher.confirmSelect();
                publisher.addReturnListener((code, text, exchange, routingKey, props, body) -> state.returned.incrementAndGet());
            }
            state.workers.add(this);
        } catch (Throwable e) { setupFailure = e; }
    }

    public SampleResult runTest(JavaSamplerContext c) {
        SampleResult r = new SampleResult();
        r.setSampleLabel("measure".equals(phase) ? c.getParameter("scenario") : phase);
        r.sampleStart();
        try {
            if ("prepare".equals(phase)) {
                state = new State(c);
                state.prepare();
            } else if ("finish".equals(phase)) {
                if (state == null) throw new IllegalStateException("No benchmark state");
                state.finish();
            } else {
                if (setupFailure != null) throw new IllegalStateException("Worker initialization failed", setupFailure);
                if (state.abort.get()) throw new IllegalStateException("Safety limit reached");
                sequence++;
                long id = (((long)worker) << 40) + sequence;
                int units = 1;
                if (state.scenario.equals("redis-get")) {
                    Object value = redis.command("GET", state.prefix + "kv:" + ThreadLocalRandom.current().nextInt(10000));
                    if (!(value instanceof byte[]) || ((byte[])value).length != 1024) throw new IOException("GET value mismatch");
                } else if (state.scenario.equals("redis-set")) {
                    Object response = redis.command("SET", state.prefix + "kv:" + ThreadLocalRandom.current().nextInt(10000), state.value, "EX", "3600");
                    if (!"OK".equals(response)) throw new IOException("SET failed");
                } else if (state.scenario.equals("redis-lua")) {
                    String sid = Long.toString(id);
                    Object response = redis.command("EVALSHA", state.sha, "6", state.prefix+"stock", state.prefix+"reservation",
                            state.prefix+"legacy", state.prefix+"pending", state.prefix+"data:"+sid, state.prefix+"time",
                            sid, "1", sid, Long.toString(System.currentTimeMillis()));
                    if (!"0".equals(text(response))) throw new IOException("Lua rejection: " + text(response));
                } else if (state.scenario.startsWith("mysql-read")) {
                    units=state.scenario.endsWith("-batch")?state.batch:1;
                    for(int operation=0;operation<units;operation++) {
                        read.setInt(1, ThreadLocalRandom.current().nextInt(10000)+1);
                        try (ResultSet rs = read.executeQuery()) {
                            if (!rs.next() || rs.getBytes(1).length != 1024) throw new SQLException("Read mismatch");
                        }
                    }
                } else if (state.scenario.startsWith("mysql-txn")) {
                    int voucher = state.scenario.equals("mysql-txn-spread") ? (int)(sequence % 64)+1 : 1;
                    try {
                        insert.setLong(1, id); insert.setLong(2, id); insert.setInt(3, voucher);
                        if (insert.executeUpdate()!=1) throw new SQLException("Insert mismatch");
                        update.setInt(1, voucher);
                        if (update.executeUpdate()!=1) throw new SQLException("Stock mismatch");
                        db.commit();
                    } catch (Exception e) { db.rollback(); throw e; }
                } else {
                    units = state.batch;
                    for (int i=0;i<units;i++) publisher.basicPublish("", state.queue, true, state.rabbitProperties, state.message);
                    publisher.waitForConfirmsOrDie(15000);
                    state.confirmed.addAndGet(units);
                }
                state.completed.addAndGet(units);
            }
            r.setSuccessful(true); r.setResponseCodeOK();
        } catch (Throwable e) {
            r.setSuccessful(false); r.setResponseCode("ERROR");
            String message = e.toString();
            if (state != null) {
                state.errors.incrementAndGet();
                for (String key : new String[]{"HMDP_DATASOURCE_PASSWORD","HMDP_REDIS_PASSWORD","HMDP_RABBITMQ_PASSWORD"}) {
                    String secret=state.env.get(key); if(secret!=null && !secret.isEmpty()) message=message.replace(secret,"[redacted]");
                }
                state.firstError.compareAndSet(null, message);
            }
            r.setResponseMessage(message); r.setStopThread(true);
        } finally { r.sampleEnd(); }
        return r;
    }

    private void closeResources() {
        close(read); close(insert); close(update); close(db); close(redis); close(publisher); close(rabbit);
    }
    public void teardownTest(JavaSamplerContext c) { closeResources(); }
    private static void close(AutoCloseable c) { if(c!=null) try {c.close();} catch(Exception ignored){} }
    private static String text(Object o) { return o instanceof byte[] ? new String((byte[])o, StandardCharsets.UTF_8) : String.valueOf(o); }

    private static class State {
        final Map<String,String> env=new HashMap<>();
        final Properties summary=new Properties();
        final AtomicLong completed=new AtomicLong(), confirmed=new AtomicLong(), acked=new AtomicLong(), returned=new AtomicLong(), errors=new AtomicLong();
        final AtomicInteger nextWorker=new AtomicInteger();
        final AtomicBoolean abort=new AtomicBoolean();
        final AtomicReference<String> firstError=new AtomicReference<>();
        final ConcurrentLinkedQueue<CapacitySampler> workers=new ConcurrentLinkedQueue<>();
        final List<com.rabbitmq.client.Connection> consumerConnections=new ArrayList<>();
        final String scenario, prefix, schema, queue, value, luaFile;
        final int batch;
        final Path resultDir;
        ScheduledExecutorService monitor;
        BufferedWriter monitorWriter;
        Redis monitorRedis;
        double redisCpuStart;
        long startMillis, processCpuLast, monitorNanoLast;
        boolean schemaOwned, queueOwned;
        String sha;
        byte[] message;
        AMQP.BasicProperties rabbitProperties;

        State(JavaSamplerContext c) throws Exception {
            for(String line:Files.readAllLines(Paths.get(c.getParameter("envFile")),StandardCharsets.UTF_8)) {
                line=line.trim(); if(line.isEmpty() || line.startsWith("#")) continue;
                int i=line.indexOf('='); if(i<1) continue;
                String val=line.substring(i+1).trim();
                if(val.length()>1 && ((val.startsWith("\"")&&val.endsWith("\""))||(val.startsWith("'")&&val.endsWith("'")))) val=val.substring(1,val.length()-1);
                env.put(line.substring(0,i).trim(),val);
            }
            scenario=c.getParameter("scenario");
            String run=c.getParameter("runId");
            if(!run.matches("[a-zA-Z0-9_]{1,45}")) throw new IllegalArgumentException("Invalid isolated run ID");
            prefix="capacity:"+run+":"; schema="hmdp_capacity_"+run; queue="hmdp.capacity."+run;
            resultDir=Paths.get(c.getParameter("resultDir")); Files.createDirectories(resultDir);
            batch=c.getIntParameter("batch",256); luaFile=c.getParameter("luaFile");
            char[] chars=new char[1024]; Arrays.fill(chars,'x'); value=new String(chars);
            summary.setProperty("scenario",scenario); summary.setProperty("threads",c.getParameter("threads"));
            summary.setProperty("batch",String.valueOf(scenario.startsWith("rabbit")||scenario.endsWith("-batch")?batch:1));
            summary.setProperty("namespace",scenario.startsWith("mysql")?schema:(scenario.startsWith("redis")?prefix:queue));
        }

        Redis redis() throws Exception { return new Redis(env.get("HMDP_REDIS_HOST"),Integer.parseInt(env.getOrDefault("HMDP_REDIS_PORT","6379")),env.get("HMDP_REDIS_PASSWORD")); }
        java.sql.Connection database() throws Exception {
            Properties p=new Properties(); p.setProperty("user",env.get("HMDP_DATASOURCE_USERNAME")); p.setProperty("password",env.get("HMDP_DATASOURCE_PASSWORD"));
            p.setProperty("connectTimeout","5000"); p.setProperty("socketTimeout","20000");
            return DriverManager.getConnection(env.get("HMDP_DATASOURCE_URL"),p);
        }
        ConnectionFactory rabbitFactory() {
            ConnectionFactory f=new ConnectionFactory(); f.setHost(env.getOrDefault("HMDP_RABBITMQ_HOST","127.0.0.1"));
            f.setPort(Integer.parseInt(env.getOrDefault("HMDP_RABBITMQ_PORT","5672"))); f.setUsername(env.get("HMDP_RABBITMQ_USERNAME"));
            f.setPassword(env.get("HMDP_RABBITMQ_PASSWORD")); f.setVirtualHost(env.getOrDefault("HMDP_RABBITMQ_VIRTUAL_HOST","/hmdp"));
            f.setAutomaticRecoveryEnabled(false); f.setConnectionTimeout(5000); return f;
        }
        void prepare() throws Exception {
            if(scenario.startsWith("redis")) {
                try(Redis c=redis()) {
                    Map<String,String> info=info(c);
                    for(String key:new String[]{"redis_version","aof_enabled","used_memory","maxmemory","maxmemory_policy"}) summary.setProperty(key,info.getOrDefault(key,"unknown"));
                    redisCpuStart=cpu(info);
                    if(scenario.equals("redis-lua")) {
                        sha=text(c.command("SCRIPT","LOAD",new String(Files.readAllBytes(Paths.get(luaFile)),StandardCharsets.UTF_8)));
                        c.command("SET",prefix+"stock","1000000000","EX","3600");
                        long now=System.currentTimeMillis(); c.command("HSET",prefix+"time","beginTimeMillis",String.valueOf(now-86400000L),"endTimeMillis",String.valueOf(now+86400000L));
                        c.command("EXPIRE",prefix+"time","3600");
                    } else {
                        for(int i=0;i<10000;i++) {
                            c.send("SET",prefix+"kv:"+i,value,"EX","3600");
                            if(i%100==99) {c.flush();for(int j=0;j<100;j++) c.receive();}
                        }
                    }
                }
                monitorRedis=redis();
            } else if(scenario.startsWith("mysql")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                try(java.sql.Connection c=database(); Statement s=c.createStatement()) {
                    summary.setProperty("mysql_version",c.getMetaData().getDatabaseProductVersion());
                    try(ResultSet rs=s.executeQuery("SHOW GLOBAL VARIABLES WHERE Variable_name IN ('innodb_flush_log_at_trx_commit','sync_binlog','log_bin','innodb_buffer_pool_size','max_connections')")) {
                        while(rs.next()) summary.setProperty(rs.getString(1),rs.getString(2));
                    }
                    try(ResultSet rs=s.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time')")) {
                        while(rs.next()) summary.setProperty(rs.getString(1)+"Before",rs.getString(2));
                    }
                    s.execute("CREATE DATABASE `"+schema+"`"); schemaOwned=true;
                    if(scenario.startsWith("mysql-read")) {
                        s.execute("CREATE TABLE `"+schema+"`.reads (id BIGINT PRIMARY KEY,payload VARBINARY(1024) NOT NULL) ENGINE=InnoDB");
                        c.setAutoCommit(false);
                        try(PreparedStatement p=c.prepareStatement("INSERT INTO `"+schema+"`.reads VALUES (?,?)")) {
                            for(int i=1;i<=10000;i++) {p.setInt(1,i);p.setBytes(2,value.getBytes(StandardCharsets.UTF_8));p.addBatch();if(i%1000==0){p.executeBatch();c.commit();}}
                        }
                    } else {
                        String source=c.getCatalog(); if(!source.matches("[a-zA-Z0-9_]+")) throw new SQLException("Invalid source schema");
                        s.execute("CREATE TABLE `"+schema+"`.orders LIKE `"+source+"`.tb_voucher_order");
                        s.execute("CREATE TABLE `"+schema+"`.stocks LIKE `"+source+"`.tb_seckill_voucher");
                        int count=scenario.equals("mysql-txn-spread")?64:1;
                        for(int i=1;i<=count;i++) s.executeUpdate("INSERT INTO `"+schema+"`.stocks (voucher_id,stock) VALUES ("+i+",1000000000)");
                        summary.setProperty("stockRows",String.valueOf(count));
                    }
                }
            } else {
                message="{\"orderId\":2090732156346306560,\"userId\":1000000000,\"voucherId\":1000000000}".getBytes(StandardCharsets.UTF_8);
                rabbitProperties=new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/json").build();
                try(com.rabbitmq.client.Connection c=rabbitFactory().newConnection("capacity-admin");Channel ch=c.createChannel()) {
                    summary.setProperty("rabbitmq_version",String.valueOf(c.getServerProperties().get("version")));
                    Map<String,Object> arguments=new HashMap<>();arguments.put("x-queue-type","classic");
                    ch.queueDeclare(queue,true,false,false,arguments);queueOwned=true;
                }
                for(int i=0;i<4;i++) {
                    com.rabbitmq.client.Connection c=rabbitFactory().newConnection("capacity-consumer-"+i);consumerConnections.add(c);
                    final Channel ch=c.createChannel();ch.basicQos(128);
                    ch.basicConsume(queue,false,(tag,delivery)->{ch.basicAck(delivery.getEnvelope().getDeliveryTag(),false);acked.incrementAndGet();},tag->{});
                }
                summary.setProperty("messageBytes",String.valueOf(message.length));summary.setProperty("consumers","4");
                summary.setProperty("queueType","classic");summary.setProperty("persistent","true");summary.setProperty("confirms","true");summary.setProperty("ackMode","manual-single");
            }
            startMillis=System.currentTimeMillis();summary.setProperty("startMillis",String.valueOf(startMillis));
            monitorWriter=Files.newBufferedWriter(resultDir.resolve("monitor.csv"),StandardCharsets.UTF_8);
            monitorWriter.write("timestamp,completed,confirmed,acked,redis_cpu_seconds,redis_ops,redis_memory_bytes,client_cpu_cores,client_heap_mb\n");monitorWriter.flush();
            monitorNanoLast=System.nanoTime();processCpuLast=processCpu();
            monitor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"capacity-monitor");t.setDaemon(true);return t;});
            monitor.scheduleWithFixedDelay(()->snapshot(),0,1,TimeUnit.SECONDS);
        }
        void snapshot() {
            try {
                long nano=System.nanoTime(),pcpu=processCpu();double cores=(pcpu-processCpuLast)/(double)(nano-monitorNanoLast);processCpuLast=pcpu;monitorNanoLast=nano;
                String cpu="",ops="",memory="";
                if(monitorRedis!=null){Map<String,String> i=info(monitorRedis);cpu=String.valueOf(cpu(i));ops=i.getOrDefault("instantaneous_ops_per_sec","");memory=i.getOrDefault("used_memory","");if(Long.parseLong(memory)>1073741824L){abort.set(true);summary.setProperty("safetyStop","Redis memory exceeded 1 GiB");}}
                Runtime rt=Runtime.getRuntime();double heap=(rt.totalMemory()-rt.freeMemory())/1048576.0;
                monitorWriter.write(System.currentTimeMillis()+","+completed.get()+","+confirmed.get()+","+acked.get()+","+cpu+","+ops+","+memory+","+cores+","+heap+"\n");monitorWriter.flush();
            } catch(Exception e){summary.setProperty("monitorError",e.getClass().getSimpleName());}
        }
        void finish() throws Exception {
            boolean valid=errors.get()==0 && !abort.get();String cleanup="ok";
            try {
                for(CapacitySampler worker:workers) worker.closeResources();
                if(scenario.startsWith("rabbit")) {
                    long deadline=System.currentTimeMillis()+20000;
                    while(acked.get()<confirmed.get() && System.currentTimeMillis()<deadline) Thread.sleep(50);
                    for(com.rabbitmq.client.Connection c:consumerConnections) close(c);
                    try(com.rabbitmq.client.Connection c=rabbitFactory().newConnection("capacity-check");Channel ch=c.createChannel()) {
                        long ready=ch.queueDeclarePassive(queue).getMessageCount(); summary.setProperty("remainingMessages",String.valueOf(ready));
                        valid=valid && ready==0 && returned.get()==0 && confirmed.get()==acked.get();
                        if(queueOwned && ready==0) ch.queueDelete(queue,false,true);
                    }
                } else if(scenario.startsWith("mysql")) {
                    try(java.sql.Connection c=database();Statement s=c.createStatement()) {
                        try(ResultSet rs=s.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time')")) {
                            while(rs.next()) summary.setProperty(rs.getString(1)+"Delta",String.valueOf(Long.parseLong(rs.getString(2))-Long.parseLong(summary.getProperty(rs.getString(1)+"Before","0"))));
                        }
                        if(!scenario.startsWith("mysql-read") && schemaOwned) {
                            long rows;try(ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM `"+schema+"`.orders")){rs.next();rows=rs.getLong(1);}
                            long stock;try(ResultSet rs=s.executeQuery("SELECT SUM(stock) FROM `"+schema+"`.stocks")){rs.next();stock=rs.getLong(1);}
                            summary.setProperty("orderRows",String.valueOf(rows));summary.setProperty("stockRemaining",String.valueOf(stock));
                            valid=valid && rows==completed.get() && stock==Long.parseLong(summary.getProperty("stockRows"))*1000000000L-rows;
                        }
                        if(schemaOwned) s.execute("DROP DATABASE `"+schema+"`");
                    }
                } else {
                    try(Redis c=redis()) {
                        Map<String,String> i=info(c); summary.setProperty("redisCpuSecondsDelta",String.valueOf(cpu(i)-redisCpuStart));
                        if(scenario.equals("redis-lua")) {
                            long stock=Long.parseLong(text(c.command("GET",prefix+"stock")));
                            long reservations=Long.parseLong(text(c.command("HLEN",prefix+"reservation")));
                            long pending=Long.parseLong(text(c.command("ZCARD",prefix+"pending")));
                            summary.setProperty("reservations",String.valueOf(reservations));summary.setProperty("pending",String.valueOf(pending));
                            valid=valid && reservations==completed.get() && pending==completed.get() && stock==1000000000L-completed.get();
                        }
                    }
                }
            } finally {
                if(monitor!=null){monitor.shutdownNow();monitor.awaitTermination(3,TimeUnit.SECONDS);} close(monitorRedis);close(monitorWriter);
                if(scenario.startsWith("redis")) {
                    try(Redis c=redis()) {for(int pass=0;pass<2;pass++){String cursor="0";do{
                        List<?> response=(List<?>)c.command("SCAN",cursor,"MATCH",prefix+"*","COUNT","1000");cursor=text(response.get(0));List<?> keys=(List<?>)response.get(1);
                        if(!keys.isEmpty()){String[] command=new String[keys.size()+1];command[0]="UNLINK";for(int j=0;j<keys.size();j++)command[j+1]=text(keys.get(j));c.command(command);}
                    }while(!cursor.equals("0"));}} catch(Exception e){cleanup=e.getClass().getSimpleName();valid=false;}
                }
                summary.setProperty("endMillis",String.valueOf(System.currentTimeMillis()));summary.setProperty("completed",String.valueOf(completed.get()));
                summary.setProperty("confirmed",String.valueOf(confirmed.get()));summary.setProperty("acked",String.valueOf(acked.get()));summary.setProperty("returned",String.valueOf(returned.get()));
                summary.setProperty("errors",String.valueOf(errors.get()));summary.setProperty("invariantsValid",String.valueOf(valid));summary.setProperty("cleanup",cleanup);
                if(firstError.get()!=null) summary.setProperty("firstError",firstError.get());
                try(OutputStream out=Files.newOutputStream(resultDir.resolve("validation.properties"))){summary.store(out,"Capacity benchmark validation; no credentials");}
            }
            if(!valid) throw new IllegalStateException("Benchmark validation failed; see validation.properties");
        }
        static Map<String,String> info(Redis c) throws Exception {Map<String,String> m=new HashMap<>();for(String line:text(c.command("INFO")).split("\r?\n")){int i=line.indexOf(':');if(i>0)m.put(line.substring(0,i),line.substring(i+1));}return m;}
        static double cpu(Map<String,String> m){return Double.parseDouble(m.getOrDefault("used_cpu_sys","0"))+Double.parseDouble(m.getOrDefault("used_cpu_user","0"));}
        static long processCpu(){return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();}
    }

    private static class Redis implements AutoCloseable {
        final Socket socket=new Socket();final BufferedInputStream input;final BufferedOutputStream output;
        Redis(String host,int port,String password) throws Exception {
            socket.connect(new InetSocketAddress(host,port),5000);socket.setSoTimeout(10000);socket.setTcpNoDelay(true);
            input=new BufferedInputStream(socket.getInputStream(),65536);output=new BufferedOutputStream(socket.getOutputStream(),65536);
            if(password!=null && !password.isEmpty()) command("AUTH",password);
        }
        synchronized Object command(String...parts) throws Exception {send(parts);flush();return receive();}
        void send(String...parts) throws IOException {
            output.write(("*"+parts.length+"\r\n").getBytes(StandardCharsets.US_ASCII));
            for(String part:parts){byte[] b=part.getBytes(StandardCharsets.UTF_8);output.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.US_ASCII));output.write(b);output.write('\r');output.write('\n');}
        }
        void flush() throws IOException {output.flush();}
        Object receive() throws IOException {
            int type=input.read();if(type<0)throw new EOFException("Redis disconnected");String line=line();
            if(type=='+')return line;if(type=='-')throw new IOException("Redis error: "+line);if(type==':')return Long.parseLong(line);
            if(type=='$'){int length=Integer.parseInt(line);if(length<0)return null;byte[] bytes=new byte[length];int offset=0;while(offset<length){int n=input.read(bytes,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}if(input.read()!=13||input.read()!=10)throw new IOException("Invalid RESP terminator");return bytes;}
            if(type=='*'){int length=Integer.parseInt(line);List<Object> list=new ArrayList<>();for(int i=0;i<length;i++)list.add(receive());return list;}throw new IOException("Invalid RESP type");
        }
        String line() throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();while(true){int c=input.read();if(c<0)throw new EOFException();if(c==13){if(input.read()!=10)throw new IOException("Invalid RESP line");return new String(b.toByteArray(),StandardCharsets.UTF_8);}b.write(c);}}
        public void close() throws IOException {socket.close();}
    }
}
