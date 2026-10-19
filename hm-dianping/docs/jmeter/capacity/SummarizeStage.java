package capacity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Streaming analysis keeps millions of JTL samples out of PowerShell memory. */
public class SummarizeStage {
    static List<String> csv(String line) {
        List<String> fields=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(quoted && i+1<line.length()&&line.charAt(i+1)=='"'){value.append('"');i++;}else quoted=!quoted;}else if(c==','&&!quoted){fields.add(value.toString());value.setLength(0);}else value.append(c);}fields.add(value.toString());return fields;
    }
    static long percentile(TreeMap<Long,Long> histogram,long count,double p){long target=(long)Math.ceil(count*p),seen=0;for(Map.Entry<Long,Long> entry:histogram.entrySet()){seen+=entry.getValue();if(seen>=target)return entry.getKey();}return 0;}
    static String quote(String text){return "\""+text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    public static void main(String[] args)throws Exception {
        Path stage=Paths.get(args[0]);String scenario=args[1];int duration=Integer.parseInt(args[2]),warmup=Integer.parseInt(args[3]),units=Integer.parseInt(args[4]),clients=Integer.parseInt(args[5]);
        long first=Long.MAX_VALUE;int prepared=0,finished=0,administrativeErrors=0;Map<String,Integer> columns=new HashMap<>();
        try(BufferedReader r=Files.newBufferedReader(stage.resolve("samples.jtl"),StandardCharsets.UTF_8)){List<String> headers=csv(r.readLine());for(int i=0;i<headers.size();i++)columns.put(headers.get(i),i);String line;while((line=r.readLine())!=null){List<String> f=csv(line);String label=f.get(columns.get("label"));if(label.equals(scenario))first=Math.min(first,Long.parseLong(f.get(columns.get("timeStamp"))));if(label.equals("prepare")||label.equals("finish")){if(label.equals("prepare"))prepared++;else finished++;if(!f.get(columns.get("success")).equals("true"))administrativeErrors++;}}}
        if(prepared!=1||finished!=1||administrativeErrors!=0)throw new IllegalStateException("Preparation/cleanup samples did not pass");
        if(first==Long.MAX_VALUE)throw new IllegalStateException("No measured samples");
        long begin=first+warmup*1000L,end=first+duration*1000L,count=0,ok=0,totalElapsed=0;TreeMap<Long,Long> histogram=new TreeMap<>();
        try(BufferedReader r=Files.newBufferedReader(stage.resolve("samples.jtl"),StandardCharsets.UTF_8)){r.readLine();String line;while((line=r.readLine())!=null){List<String> f=csv(line);if(!f.get(columns.get("label")).equals(scenario))continue;long timestamp=Long.parseLong(f.get(columns.get("timeStamp"))),elapsed=Long.parseLong(f.get(columns.get("elapsed")));if(timestamp<begin||timestamp+elapsed>end)continue;count++;if(f.get(columns.get("success")).equals("true")){ok++;totalElapsed+=elapsed;histogram.put(elapsed,histogram.getOrDefault(elapsed,0L)+1);}}}
        double clientCpu=0,redisCpuFirst=0,redisCpuLast=0;long metricCount=0,redisTimeFirst=0,redisTimeLast=0,metricTimeFirst=0,metricTimeLast=0,confirmedFirst=0,confirmedLast=0,ackedFirst=0,ackedLast=0;boolean redis=scenario.startsWith("redis");
        try(BufferedReader r=Files.newBufferedReader(stage.resolve("monitor.csv"),StandardCharsets.UTF_8)){r.readLine();String line;while((line=r.readLine())!=null){List<String> f=csv(line);long timestamp=Long.parseLong(f.get(0));if(timestamp<begin||timestamp>end)continue;clientCpu+=Double.parseDouble(f.get(7));metricCount++;if(metricTimeFirst==0){metricTimeFirst=timestamp;confirmedFirst=Long.parseLong(f.get(2));ackedFirst=Long.parseLong(f.get(3));}metricTimeLast=timestamp;confirmedLast=Long.parseLong(f.get(2));ackedLast=Long.parseLong(f.get(3));if(redis){double c=Double.parseDouble(f.get(4));if(redisTimeFirst==0){redisTimeFirst=timestamp;redisCpuFirst=c;}redisTimeLast=timestamp;redisCpuLast=c;}}}
        Properties validation=new Properties();try(InputStream in=Files.newInputStream(stage.resolve("validation.properties"))){validation.load(in);}
        Locale.setDefault(Locale.US);
        String json="{\"scenario\":"+quote(scenario)+",\"clients\":"+clients+",\"batch\":"+units+",\"seconds\":"+(duration-warmup)+",\"samples\":"+count+",\"successfulUnits\":"+(ok*units)+",\"successfulPerSecond\":"+String.format("%.2f",ok*units/(double)(duration-warmup))+",\"errors\":"+(count-ok)+",\"errorPercent\":"+String.format("%.4f",count==0?100:(count-ok)*100.0/count)+",\"avgMs\":"+String.format("%.3f",ok==0?0:totalElapsed/(double)ok)+",\"p95Ms\":"+percentile(histogram,ok,.95)+",\"p99Ms\":"+percentile(histogram,ok,.99)+",\"clientCpuCores\":"+String.format("%.2f",metricCount==0?0:clientCpu/metricCount)+",\"redisCpuCores\":"+(redis&&redisTimeLast>redisTimeFirst?String.format("%.3f",(redisCpuLast-redisCpuFirst)*1000/(redisTimeLast-redisTimeFirst)):"null")+",\"invariantsValid\":"+quote(validation.getProperty("invariantsValid","false"))+",\"stage\":"+quote(stage.toString())+"}";
        if(scenario.startsWith("rabbit") && metricTimeLast>metricTimeFirst){json=json.substring(0,json.length()-1)+",\"consumedPerSecond\":"+String.format("%.2f",(ackedLast-ackedFirst)*1000.0/(metricTimeLast-metricTimeFirst))+",\"confirmedMinusAckedStart\":"+(confirmedFirst-ackedFirst)+",\"confirmedMinusAckedEnd\":"+(confirmedLast-ackedLast)+"}";}
        Files.write(stage.resolve("summary.json"),json.getBytes(StandardCharsets.UTF_8));System.out.println(json);
        if(!validation.getProperty("invariantsValid","false").equals("true")||ok==0)System.exit(2);
    }
}
