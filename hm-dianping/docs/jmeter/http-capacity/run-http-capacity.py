"""Native JMeter HTTP sampler plans, isolated setup, and per-endpoint statistics.

Tokens remain in target/ and are never put into command arguments or JTL fields.
"""
import argparse, collections, csv, ctypes, json, math, os, pathlib, subprocess, sys, time
import xml.etree.ElementTree as ET

HERE=pathlib.Path(__file__).resolve().parent
MODULE=HERE.parents[2]
JMETER=pathlib.Path(r'F:\hmdpcode\apache-jmeter-5.6.3\apache-jmeter-5.6.3')
RUNTIME=(MODULE/'target/jdbc-connection-check/runtime-classpath.txt').read_text(encoding='utf-8-sig').strip()
SUPPORT_CP=str(MODULE/'target/http-capacity/classes')+';'+RUNTIME

def read_json(p):
    return json.loads(pathlib.Path(p).read_text(encoding='utf-8-sig'))
def write_json(p,data):
    pathlib.Path(p).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
def support(action,root,*args,log=None):
    cmd=['java','-cp',SUPPORT_CP,'HttpCapacitySupport',action,str(MODULE/'.env'),str(root),*map(str,args)]
    return subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT if log else None,check=True)

PREPROCESS=r'''
long now=System.currentTimeMillis()
if(props.getProperty('mode')!='burst' && (now>=(props.get('endMs') as long) || new File(props.getProperty('stageDir'),'stop-requests').exists())) { ctx.getThread().stop(); return }
vars.put('outcome','');vars.put('orderId','');vars.put('e2eMs','');vars.put('orderStatus','PROCESSING')
vars.put('deadlineMs',String.valueOf(now+60000L))
'''
POSTPROCESS=r'''
import groovy.json.JsonSlurper
def mode=props.getProperty('mode')
try {
    def body=new JsonSlurper().parseText(prev.getResponseDataAsString())
    if(mode=='shop') {
        boolean ok=prev.getResponseCode()=='200' && body.success==true && body.data?.id!=null
        vars.put('outcome',ok?'OK':'BUSINESS_ERROR');prev.setSuccessful(ok)
    } else if(prev.getResponseCode()=='200' && body.success==true && body.data!=null) {
        vars.put('orderId',body.data.toString());vars.put('outcome','ACCEPTED');vars.put('requestStart',String.valueOf(prev.getStartTime()));prev.setSuccessful(true)
    } else {
        String msg=(body.errorMsg?:'').toString()
        String outcome=msg.contains('抢购人数过多')?'RATE_LIMIT':msg.contains('库存不足')?'SOLD_OUT':(msg.contains('重复')||msg.contains('已经抢到'))?'DUPLICATE':'BUSINESS_ERROR'
        vars.put('outcome',outcome)
        prev.setSuccessful(props.getProperty('expectedRejects','').tokenize(',').contains(outcome))
    }
} catch(Exception e) {vars.put('outcome','HTTP_ERROR');prev.setSuccessful(false)}
prev.setResponseMessage(vars.get('outcome'))
'''
POLL_POST=r'''
import groovy.json.JsonSlurper
try {
    def b=new JsonSlurper().parseText(prev.getResponseDataAsString())
    String status=b.data?.status?.toString()?:'NOT_FOUND'
    vars.put('orderStatus',status)
    prev.setSuccessful(prev.getResponseCode()=='200' && b.success==true && status!='FAILED' && status!='NOT_FOUND')
    if(status=='COMPLETED') vars.put('e2eMs',String.valueOf(prev.getEndTime()-(vars.get('requestStart') as long)))
} catch(Exception e) {prev.setSuccessful(false);vars.put('orderStatus','HTTP_ERROR')}
'''
FINAL=r'''
boolean hasOrder=vars.get('orderId')!=null && !vars.get('orderId').isEmpty()
boolean ok=hasOrder ? vars.get('orderStatus')=='COMPLETED' : props.getProperty('expectedRejects','').tokenize(',').contains(vars.get('outcome'))
SampleResult.setSuccessful(ok);SampleResult.setResponseCode(ok?'200':'500')
SampleResult.setResponseMessage(hasOrder?vars.get('orderStatus'):vars.get('outcome'))
'''
SETUP=r'''
import groovy.json.JsonOutput
long start=System.currentTimeMillis()
long warm=props.getProperty('warmup','15') as long
long seconds=props.getProperty('seconds','60') as long
props.put('endMs',start+(warm+seconds)*1000L)
props.put('pacingCounter',new java.util.concurrent.atomic.AtomicLong(0L))
props.remove('pacingAnchorNs')
new File(props.getProperty('stageDir'),'clock.json').text=JsonOutput.toJson([startMs:start,measureStartMs:start+warm*1000L,endMs:start+(warm+seconds)*1000L])
'''
PRECISE_TIMER=r'''
import java.util.concurrent.locks.LockSupport
double rate=props.getProperty('targetRps','0') as double
if(rate>0) {
    synchronized(props) { if(!props.containsKey('pacingAnchorNs')) props.put('pacingAnchorNs',System.nanoTime()) }
    long index=props.get('pacingCounter').getAndIncrement()
    long due=(props.get('pacingAnchorNs') as long)+(long)(index*1000000000d/rate)
    long remaining=due-System.nanoTime()
    while(remaining>0 && !Thread.currentThread().isInterrupted()) {LockSupport.parkNanos(remaining);remaining=due-System.nanoTime()}
}
return 0L
'''

def prop(node,name,value,kind='stringProp'):
    ET.SubElement(node,kind,{'name':name}).text=str(value)
def element(tree,tag,name,gui=None):
    n=ET.SubElement(tree,tag,{'guiclass':gui or tag+'Gui','testclass':tag,'testname':name,'enabled':'true'})
    return n,ET.SubElement(tree,'hashTree')
def script(tree,kind,name,text):
    n,t=element(tree,kind,name,'TestBeanGUI');prop(n,'scriptLanguage','groovy');prop(n,'cacheKey','true');prop(n,'script',text);return n,t
def loops(node,count):
    p=ET.SubElement(node,'elementProp',{'name':'ThreadGroup.main_controller','elementType':'LoopController','guiclass':'LoopControlPanel','testclass':'LoopController','testname':'Loop'})
    prop(p,'LoopController.continue_forever','false','boolProp');prop(p,'LoopController.loops',count)
def http(tree,name,path,method):
    n,t=element(tree,'HTTPSamplerProxy',name,'HttpTestSampleGui')
    args=ET.SubElement(n,'elementProp',{'name':'HTTPsampler.Arguments','elementType':'Arguments'});ET.SubElement(args,'collectionProp',{'name':'Arguments.arguments'})
    for k,v in {'domain':'127.0.0.1','port':'8082','protocol':'http','path':path,'method':method,'implementation':'HttpClient4','connect_timeout':'3000','response_timeout':'20000'}.items():prop(n,'HTTPSampler.'+k,v)
    prop(n,'HTTPSampler.use_keepalive','true','boolProp');prop(n,'HTTPSampler.follow_redirects','false','boolProp');return n,t
def make_plan(path,mode,flow,precise=False,warm_client=False):
    root=ET.Element('jmeterTestPlan',{'version':'1.2','properties':'5.0','jmeter':'5.6.3'});tree=ET.SubElement(root,'hashTree')
    plan,pt=element(tree,'TestPlan','HTTP capacity','TestPlanGui');prop(plan,'TestPlan.serialize_threadgroups','false','boolProp')
    setup,st=element(pt,'SetupThreadGroup','Clock','SetupThreadGroupGui');loops(setup,'1');prop(setup,'ThreadGroup.num_threads','1');prop(setup,'ThreadGroup.ramp_time','0')
    if warm_client:http(st,'client-warmup','/shop/1','GET')
    script(st,'JSR223Sampler','clock-setup',SETUP)
    group,gt=element(pt,'ThreadGroup','HTTP clients','ThreadGroupGui');loops(group,'1' if mode=='burst' else '-1')
    prop(group,'ThreadGroup.num_threads','${__P(threads,20)}');prop(group,'ThreadGroup.ramp_time','${__P(ramp,2)}');prop(group,'ThreadGroup.scheduler','false','boolProp');prop(group,'ThreadGroup.on_sample_error','continue')
    if mode!='shop':
        data,_=element(gt,'CSVDataSet','Distinct test identities','TestBeanGUI')
        for k,v in {'filename':'${__P(tokensFile)}','fileEncoding':'UTF-8','variableNames':'token,userId','delimiter':',','shareMode':'shareMode.all'}.items():prop(data,k,v)
        for k,v in {'ignoreFirstLine':'false','quotedData':'false','recycle':'false','stopThread':'true'}.items():prop(data,k,v,'boolProp')
        headers,_=element(gt,'HeaderManager','Authentication','HeaderPanel');coll=ET.SubElement(headers,'collectionProp',{'name':'HeaderManager.headers'})
        for name,value in [('authorization','${token}'),('Accept','application/json')]:
            e=ET.SubElement(coll,'elementProp',{'name':name,'elementType':'Header'});prop(e,'Header.name',name);prop(e,'Header.value',value)
    if warm_client:
        once,ot=element(gt,'OnceOnlyController','Prepare each HTTP connection','OnceOnlyControllerGui')
        http(ot,'connection-warmup','/shop/1','GET')
    work=gt
    if flow:
        transaction,work=element(gt,'TransactionController','order-flow','TransactionControllerGui');prop(transaction,'TransactionController.parent','true','boolProp');prop(transaction,'TransactionController.includeTimers','true','boolProp')
    req,rt=http(work,'shop-read' if mode=='shop' else 'submit-order','/shop/1' if mode=='shop' else '/voucher-order/seckill/${__P(voucherId)}','GET' if mode=='shop' else 'POST')
    script(rt,'JSR223PreProcessor','Request window',PREPROCESS)
    if mode=='burst':
        timer,_=element(rt,'SyncTimer','Release one wave','TestBeanGUI');prop(timer,'groupSize','${__P(threads)}');prop(timer,'timeoutInMs','30000')
    elif precise:
        script(rt,'JSR223Timer','Absolute request schedule',PRECISE_TIMER)
    else:
        timer,_=element(rt,'ConstantThroughputTimer','Request pacing','TestBeanGUI');prop(timer,'throughput','${__P(tpm,1000000000)}');prop(timer,'calcMode','4','intProp')
    script(rt,'JSR223PostProcessor','Business result',POSTPROCESS)
    if flow:
        wh,wt=element(work,'WhileController','Wait for completion','WhileControllerGui')
        prop(wh,'WhileController.condition',"${__groovy(vars.get('orderId') && vars.get('orderStatus')!='COMPLETED' && vars.get('orderStatus')!='FAILED' && vars.get('orderStatus')!='NOT_FOUND' && System.currentTimeMillis()<(vars.get('deadlineMs') as long))}")
        poll,pollt=http(wt,'order-status','/voucher-order/status/${orderId}?voucherId=${__P(voucherId)}','GET')
        timer,_=element(pollt,'ConstantTimer','Poll interval','ConstantTimerGui');prop(timer,'ConstantTimer.delay','${__P(pollMs,250)}')
        script(pollt,'JSR223PostProcessor','Completion status',POLL_POST)
        script(work,'JSR223Sampler','order-final',FINAL)
    ET.indent(root,space='  ');ET.ElementTree(root).write(path,encoding='utf-8',xml_declaration=True)

def percentile(hist,p):
    total=sum(hist.values())
    if not total:return None
    target=math.ceil(total*p);n=0
    for value,count in sorted(hist.items()):
        n+=count
        if n>=target:return value
def latency(hist):
    n=sum(hist.values());return {'count':n,'meanMs':round(sum(k*v for k,v in hist.items())/n,2) if n else None,'p95Ms':percentile(hist,.95),'p99Ms':percentile(hist,.99)}

def cpu_seconds(pid):
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.restype=ctypes.c_void_p
    kernel.GetProcessTimes.argtypes=[ctypes.c_void_p,*([ctypes.POINTER(ctypes.c_ulonglong)]*4)]
    kernel.CloseHandle.argtypes=[ctypes.c_void_p]
    handle=kernel.OpenProcess(0x1000,False,pid)
    if not handle:return None
    try:
        created,exited,system,user=(ctypes.c_ulonglong() for _ in range(4))
        if not kernel.GetProcessTimes(handle,ctypes.byref(created),ctypes.byref(exited),ctypes.byref(system),ctypes.byref(user)):return None
        return (system.value+user.value)/10000000
    finally:kernel.CloseHandle(handle)

def analyze(stage,quiet=False):
    meta=read_json(stage/'stage.json');clock=read_json(stage/'clock.json');validation=read_json(stage/'validation.json')
    begin,end=clock['measureStartMs'],clock['endMs'];wave=meta['mode']=='burst'
    outcomes=collections.Counter();whole=collections.Counter();rt=collections.Counter();client=collections.Counter();events=collections.Counter();accepted_ids=set();submit_starts={};starts=[];finishes=[];peak_threads=0;http_errors=0;last_interval={};rounding_adjustments=0;flow_finals=0;flow_failures=0;connection_warmups=0;connection_warmup_failures=0;status_requests=0;status_errors=0
    with (stage/'samples.jtl').open(encoding='utf-8-sig',newline='') as f:
        for row in csv.DictReader(f):
            label=row['label'];ts=int(row['timeStamp']);elapsed=int(row['elapsed']);finish=ts+elapsed
            if label=='connection-warmup':
                connection_warmups+=1
                if row['success']!='true':connection_warmup_failures+=1
            if label=='order-status' and (wave or begin<=ts and finish<=end):
                status_requests+=1
                if row['success']!='true':status_errors+=1
            if label in ('submit-order','shop-read'):
                outcome=row.get('outcome','UNKNOWN');whole[outcome]+=1
                if outcome=='ACCEPTED':accepted_ids.add(row.get('orderId'));submit_starts[row.get('orderId')]=ts
                starts.append(ts);finishes.append(finish)
                if wave or begin<=ts and finish<=end:
                    outcomes[outcome]+=1;rt[elapsed]+=1;peak_threads=max(peak_threads,int(row.get('allThreads',0)))
                    if row['success']!='true':http_errors+=1
                if wave or finish>=begin and ts<end:
                    low=ts if wave else max(ts,begin);high=finish if wave else min(finish,end)
                    thread=row['threadName'];previous=last_interval.get(thread)
                    # CSV timestamps have millisecond precision. Consecutive serial
                    # requests occasionally overlap by 1 ms after rounding.
                    if previous and previous[1]>low:
                        events[previous[1]]+=1;events[max(previous[0],low)]-=1;rounding_adjustments+=1
                    if high>low:events[low]+=1;events[high]-=1;last_interval[thread]=(low,high)
            elif label=='order-final' and row.get('orderId'):
                ts0=submit_starts.get(row.get('orderId'),-1)
                if wave or begin<=ts0<end:
                    flow_finals+=1
                    if row['success']=='true' and row.get('e2eMs'):client[int(row['e2eMs'])]+=1
                    else:flow_failures+=1
    seconds=max(.001,(max(finishes)-min(starts))/1000) if wave and starts else (end-begin)/1000
    active=peak=0
    for _,delta in sorted(events.items()):active+=delta;peak=max(peak,active)
    server=collections.Counter();queue=collections.Counter();dbtime=collections.Counter();commits=0;all_complete=0;database_ids=set()
    with (stage/'orders.csv').open(encoding='utf-8',newline='') as f:
        for row in csv.DictReader(f):
            database_ids.add(row['orderId'])
            if row['status']!='ORDER_CREATED':continue
            all_complete+=1;accepted=int(row['acceptedAt']);committed=int(row['dbCommittedAt'])
            if wave or begin<=accepted<end:
                for hist,col in [(server,'serverE2eMs'),(queue,'queueDelayMs'),(dbtime,'dbProcessMs')]:
                    if row[col]:hist[int(row[col])]+=1
            if wave or begin<=committed<end:commits+=1
    monitor=list(csv.DictReader((stage/'monitor.csv').open(encoding='utf-8',newline='')))
    measured=[r for r in monitor if begin<=int(r['timestamp'])<=end] if not wave else monitor
    ready=[int(r['queueReady']) for r in measured];lags=[int(r['reservations'])-int(r['orders']) for r in measured]
    row_wait_delta=int(measured[-1]['rowLockWaits'])-int(measured[0]['rowLockWaits']) if len(measured)>1 else 0
    count=sum(outcomes.values());positive=outcomes['OK'] if meta['mode']=='shop' else outcomes['ACCEPTED']
    exhausted=sum(whole.values())>=100000 and meta['mode']!='shop'
    invariant=bool(validation['invariantsValid']) and (meta['mode']=='shop' or database_ids==accepted_ids) and all_complete==validation['orders']
    report={**meta,'measurementSeconds':round(seconds,3),'requests':count,'actualRequestRate':round(count/seconds,2),'successfulRate':round(positive/seconds,2),'outcomes':dict(outcomes),'wholeRunOutcomes':dict(whole),'unexpectedErrors':http_errors,'businessSuccessPercent':round(100*positive/count,4) if count else 0,
        'acceptanceLatency':latency(rt),'serverCompletionLatency':latency(server),'clientCompletionLatency':latency(client),'queueLatency':latency(queue),'dbProcessingLatency':latency(dbtime),'completionRate':None if wave else round(commits/seconds,2),'peakClientInflightHttp':peak,'peakReportedThreads':peak_threads,'requestStartSpreadMs':max(starts)-min(starts) if starts else None,
        'queueReadyStart':ready[0] if ready else None,'queueReadyEnd':ready[-1] if ready else None,'queueReadyPeak':max(ready) if ready else None,'outstandingStart':lags[0] if lags else None,'outstandingEnd':lags[-1] if lags else None,'rowLockWaitDelta':row_wait_delta,'inputExhausted':exhausted,'safetyStopped':(stage/'stop-requests').exists(),'invariantsValid':invariant,'validation':validation,'timestampRoundingAdjustments':rounding_adjustments}
    p95=report['acceptanceLatency']['p95Ms'];e95=report['serverCompletionLatency']['p95Ms']
    client95=report['clientCompletionLatency']['p95Ms']
    report['flowFinalCount']=flow_finals;report['flowFailureCount']=flow_failures
    report['connectionWarmups']=connection_warmups;report['connectionWarmupFailures']=connection_warmup_failures
    report['statusQueryCount']=status_requests;report['statusQueryRate']=None if wave else round(status_requests/seconds,2);report['statusQueryErrors']=status_errors
    report['wholeRunRequestCount']=sum(whole.values());report['wholeRunTransportErrors']=whole['HTTP_ERROR']
    client_ok=not meta.get('flow') or client95 is not None and client95<=2000 and flow_failures/max(1,flow_finals)<=.001
    tail=measured[-30:]
    slope=0.0
    if len(tail)>2:
        xs=[(int(r['timestamp'])-int(tail[0]['timestamp']))/1000 for r in tail];ys=[int(r['reservations'])-int(r['orders']) for r in tail]
        xm=sum(xs)/len(xs);ym=sum(ys)/len(ys);den=sum((x-xm)**2 for x in xs)
        if den:slope=sum((x-xm)*(y-ym) for x,y in zip(xs,ys))/den
    report['tailBacklogSlopePerSecond']=round(slope,4)
    # Closed-loop flows wait for completion before sending another order, so
    # their outstanding count is inherently bounded by the thread count. One
    # endpoint snapshot can fluctuate substantially and is not a growth trend.
    stable_queue=meta['mode']!='post' or (report['completionRate']>=report['successfulRate']*.995 and slope<=max(.5,report['successfulRate']*.002))
    report['queueStableInMeasurement']=stable_queue
    connections_ready=not(wave and meta.get('clientPrewarmed')) or connection_warmups==meta['threads'] and connection_warmup_failures==0
    report['meetsProposedSlo']=bool(count and http_errors/count<=.001 and report['businessSuccessPercent']>=99.9 and p95 is not None and p95<=(200 if meta['mode']=='shop' else 300) and (meta['mode']=='shop' or e95 is not None and e95<=2000) and client_ok and invariant and not exhausted and not report['safetyStopped'] and stable_queue and connections_ready)
    consumer_counts=[int(r['consumers']) for r in measured if r.get('consumers')]
    report['consumerCountRange']=[min(consumer_counts),max(consumer_counts)] if consumer_counts else None
    if len(measured)>1:
        report['redisCpuCores']=round((float(measured[-1]['redisCpuSeconds'])-float(measured[0]['redisCpuSeconds']))/((int(measured[-1]['timestamp'])-int(measured[0]['timestamp']))/1000),3)
    if (stage/'resources.json').exists():report['resources']=read_json(stage/'resources.json')
    write_json(stage/'summary.json',report)
    compact={k:report[k] for k in ['name','mode','threads','targetRps','actualRequestRate','successfulRate','completionRate','peakClientInflightHttp','queueReadyPeak','outstandingStart','outstandingEnd','invariantsValid','meetsProposedSlo']}
    compact['acceptanceP95Ms']=p95;compact['serverCompletionP95Ms']=e95;compact['clientCompletionP95Ms']=client95
    if not quiet:print(json.dumps(compact,ensure_ascii=False),flush=True)
    return report

def run_stage(args):
    root=pathlib.Path(args.root).resolve();stage=root/args.name
    if stage.exists():raise RuntimeError('Stage already exists: '+str(stage))
    stage.mkdir();meta={'name':args.name,'mode':args.mode,'threads':args.threads,'targetRps':args.rps,'warmup':args.warmup,'seconds':args.seconds,'flow':args.flow,'expectedRejects':args.expected,'rateLimit':args.limit,'pacing':'absolute-nanosecond' if getattr(args,'precise',False) else 'jmeter-constant-throughput','clientPrewarmed':getattr(args,'warm_client',False)}
    write_json(stage/'stage.json',meta);support('voucher',root,stage,args.stock);meta=read_json(stage/'stage.json')
    token_file=root/'tokens.csv'
    if args.duplicate:
        first=token_file.open(encoding='utf-8').readline();token_file=stage/'duplicate-tokens.csv';token_file.write_text(first*args.threads,encoding='utf-8')
    plan=stage/'test.jmx';make_plan(plan,args.mode,args.flow,getattr(args,'precise',False),getattr(args,'warm_client',False))
    monlog=(stage/'monitor.log').open('w',encoding='utf-8')
    monitor=subprocess.Popen(['java','-cp',SUPPORT_CP,'HttpCapacitySupport','monitor',str(MODULE/'.env'),str(root),str(stage)],stdout=monlog,stderr=subprocess.STDOUT)
    command=['java','-Xms256m','-Xmx1024m','-jar',str(JMETER/'bin/ApacheJMeter.jar'),'-n','-t',str(plan),'-l',str(stage/'samples.jtl'),'-j',str(stage/'jmeter.log')]
    properties={'mode':args.mode,'stageDir':str(stage),'threads':args.threads,'ramp':args.ramp,'warmup':args.warmup,'seconds':args.seconds,'tokensFile':str(token_file),'voucherId':meta['voucherId'],'expectedRejects':args.expected,'tpm':args.rps*60 if args.rps else 1000000000,'targetRps':args.rps,'pollMs':args.poll,'sample_variables':'outcome,orderId,userId,e2eMs','jmeter.save.saveservice.response_data':'false','jmeter.save.saveservice.response_message':'true','jmeter.save.saveservice.timestamp_format':'ms','jmeter.save.saveservice.url':'false'}
    command += ['-J'+k+'='+str(v) for k,v in properties.items()]
    print('START '+args.name,flush=True)
    try:
        app_pid=read_json(root/'application-process.json')['pid'];app_cpu=cpu_seconds(app_pid);wall=time.monotonic()
        with (stage/'console.log').open('w',encoding='utf-8') as out:
            process=subprocess.Popen(command,stdout=out,stderr=subprocess.STDOUT)
            try:code=process.wait(timeout=args.seconds+args.warmup+180)
            except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=20);raise
            if code:raise subprocess.CalledProcessError(code,command)
            jmeter_cpu=cpu_seconds(process.pid)
        after=cpu_seconds(app_pid);elapsed=time.monotonic()-wall
        write_json(stage/'resources.json',{'wholeRunWallSeconds':round(elapsed,3),'appCpuCores':round((after-app_cpu)/elapsed,3) if app_cpu is not None and after is not None else None,'jmeterCpuSeconds':jmeter_cpu})
        support('validate',root,stage)
    finally:
        (stage/'stop-monitor').touch()
        try:monitor.wait(timeout=25)
        except subprocess.TimeoutExpired:monitor.terminate();monitor.wait(timeout=10)
        monlog.close()
    return analyze(stage)

def run_suite(args):
    suites={
        'read-refine':[{'name':'shop-'+str(n),'mode':'shop','threads':n} for n in (600,700)],
        'rates':[{'name':'rate-'+str(n),'mode':'post','threads':200,'rps':n} for n in (50,100,150,200,250,300,500,750,1000)],
        'flows':[{'name':'flow-'+str(n),'mode':'flow','threads':n,'flow':True} for n in (50,100,200,400,800,1000)],
        'bursts':[{'name':'burst-'+str(n),'mode':'burst','threads':n,'flow':True,'warmup':0} for n in (100,200,500,1000)],
        'warm-bursts':[{'name':'warm-burst-'+str(n),'mode':'burst','threads':n,'flow':True,'warmup':0,'warm_client':True} for n in (100,200,500,1000)],
        'correctness':[
            {'name':'duplicate-100','mode':'burst','threads':100,'stock':100,'flow':True,'duplicate':True,'warmup':0,'expected':'DUPLICATE'},
            {'name':'soldout-500','mode':'burst','threads':500,'stock':100,'flow':True,'warmup':0,'expected':'SOLD_OUT'}],
    }
    suites['remaining']=[{'name':'precise-rate-240','mode':'post','threads':200,'rps':240,'precise':True}]+suites['flows']+suites['bursts']+suites['correctness']+suites['read-refine']
    suites['confirm-final']=[]
    suites['recovered-final']=[
        {'name':'recovered-flow-100-180','mode':'flow','threads':100,'flow':True,'warmup':75,'seconds':180,'warm_client':True},
        {'name':'recovered-flow-400-180','mode':'flow','threads':400,'flow':True,'warmup':75,'seconds':180,'warm_client':True},
        {'name':'recovered-shop-500-180','mode':'shop','threads':500,'warmup':15,'seconds':180,'warm_client':True},
    ]
    overloaded=0
    for case in suites[args.suite]:
        options=vars(args).copy();options.update({'mode':'post','rps':0,'threads':50,'flow':False,'duplicate':False,'expected':'','stock':100000});options.update(case)
        stage=pathlib.Path(args.root)/case['name']
        if (stage/'summary.json').exists():result=read_json(stage/'summary.json');print('EXISTING '+case['name'],flush=True)
        else:result=run_stage(argparse.Namespace(**options))
        if args.suite=='recovered-final':
            while not result['meetsProposedSlo'] and options['threads']>100:
                options['threads']-=100;options['name']='recovered-'+case['mode']+'-'+str(options['threads'])+'-180'
                stage=pathlib.Path(args.root)/options['name']
                result=read_json(stage/'summary.json') if (stage/'summary.json').exists() else run_stage(argparse.Namespace(**options))
        if args.suite=='rates':
            overloaded=overloaded+1 if not result['meetsProposedSlo'] and result.get('outstandingEnd',0)>500 else 0
            if overloaded>=2:
                print('RATE_SEARCH_STOP: two consecutive overload stages; refine around last stable rate instead of accumulating more messages.',flush=True);break
    if args.suite=='remaining':
        candidates=[]
        for file in pathlib.Path(args.root).glob('*/summary.json'):
            r=read_json(file)
            if r.get('mode')=='post' and r['meetsProposedSlo'] and r.get('pacing')=='absolute-nanosecond':candidates.append(r)
        for candidate in sorted(candidates,key=lambda r:r['successfulRate'],reverse=True):
            rate=int(candidate['targetRps']);options=vars(args).copy();options.update({'name':'confirm-rate-'+str(rate)+'-180','mode':'post','threads':200,'rps':rate,'precise':True,'flow':False,'duplicate':False,'expected':'','stock':100000,'seconds':180,'warmup':15})
            result=run_stage(argparse.Namespace(**options))
            if result['meetsProposedSlo']:break
    if args.suite=='confirm-final':
        cases=[{'name':'steady-rate-240-180','mode':'post','threads':200,'rps':240,'precise':True,'warmup':75,'seconds':180,'flow':False,'warm_client':True},
               {'name':'confirm-flow-100-180','mode':'flow','threads':100,'rps':0,'warmup':75,'seconds':180,'flow':True,'warm_client':True}]
        all_results=[]
        for folder in pathlib.Path(args.root).iterdir():
            if folder.is_dir() and (folder/'validation.json').exists():all_results.append(analyze(folder))
        for mode,prefix in [('flow','confirm-flow'),('shop','confirm-shop')]:
            candidates=[r for r in all_results if r['mode']==mode and r['meetsProposedSlo'] and r['name'].startswith(mode+'-')]
            if candidates:
                n=max(r['threads'] for r in candidates)
                cases.append({'name':prefix+'-'+str(n)+'-180','mode':mode,'threads':n,'rps':0,'warmup':75 if mode=='flow' else 15,'seconds':180,'flow':mode=='flow','warm_client':True})
        for case in cases:
            options=vars(args).copy();options.update({'flow':False,'duplicate':False,'expected':'','stock':100000,'rps':0});options.update(case)
            stage=pathlib.Path(args.root)/case['name']
            result=read_json(stage/'summary.json') if (stage/'summary.json').exists() else run_stage(argparse.Namespace(**options))
            while case['mode'] in ('flow','shop') and not result['meetsProposedSlo'] and options['threads']>100:
                options['threads']-=100;options['name']='confirm-'+case['mode']+'-'+str(options['threads'])+'-180'
                stage=pathlib.Path(args.root)/options['name']
                result=read_json(stage/'summary.json') if (stage/'summary.json').exists() else run_stage(argparse.Namespace(**options))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--name');p.add_argument('--suite',choices=['read-refine','rates','flows','bursts','warm-bursts','correctness','remaining','confirm-final','recovered-final']);p.add_argument('--mode',choices=['shop','post','flow','burst'],default='post');p.add_argument('--threads',type=int,default=50);p.add_argument('--rps',type=float,default=0);p.add_argument('--warmup',type=int,default=15);p.add_argument('--seconds',type=int,default=60);p.add_argument('--stock',type=int,default=100000);p.add_argument('--ramp',type=int,default=2);p.add_argument('--poll',type=int,default=250);p.add_argument('--expected',default='');p.add_argument('--limit',choices=['true','false'],default='false');p.add_argument('--flow',action='store_true');p.add_argument('--duplicate',action='store_true');p.add_argument('--precise',action='store_true');p.add_argument('--warm-client',action='store_true');p.add_argument('--analyze',action='store_true');a=p.parse_args()
    if a.mode=='flow':a.flow=True
    if a.suite:run_suite(a)
    elif not a.name:p.error('--name or --suite is required')
    elif a.analyze:analyze(pathlib.Path(a.root)/a.name)
    else:run_stage(a)
