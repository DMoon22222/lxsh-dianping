"""Recheck raw measurements, export endpoint-only JTLs and a compact report."""
import argparse,csv,datetime,importlib.util,json,pathlib,subprocess,zipfile

HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('capacity_runner',HERE/'run-http-capacity.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

def filtered_jtl(stage,output,label):
    clock=runner.read_json(stage/'clock.json')
    with (stage/'samples.jtl').open(encoding='utf-8-sig',newline='') as src,output.open('w',encoding='utf-8',newline='') as dst:
        # JMeter distinguishes custom variables by quoted header names. Keep
        # its original header rather than reserializing it with minimal quoting.
        header=src.readline();fields=next(csv.reader([header]));dst.write(header)
        rows=csv.DictReader(src,fieldnames=fields);writer=csv.DictWriter(dst,fieldnames=fields);n=0
        for r in rows:
            start=int(r['timeStamp']);end=start+int(r['elapsed'])
            if r['label']==label and clock['measureStartMs']<=start and end<=clock['endMs']:
                writer.writerow(r);n+=1
    return n

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--html',action='store_true');p.add_argument('--archive',action='store_true');p.add_argument('--reuse-summaries',action='store_true',help='Reuse previously recalculated JSON when retrying report rendering only');a=p.parse_args()
    root=pathlib.Path(a.root).resolve();results=[]
    background_path=root/'diagnostics/background-load.json'
    background=runner.read_json(background_path) if background_path.exists() else None
    for stage in sorted(root.iterdir()):
        if stage.is_dir() and (stage/'validation.json').exists() and (stage/'samples.jtl').exists():
            r=runner.read_json(stage/'summary.json') if a.reuse_summaries else runner.analyze(stage,quiet=True)
            if background:
                clock=runner.read_json(stage/'clock.json')
                recovered_at=background.get('additionalLoadAbsentObservedMs')
                r['environmentCondition']='earlier-baseline' if clock['endMs']<=background['additionalLoadStartedMs'] else 'recovered' if recovered_at and clock['startMs']>=recovered_at else 'additional-load-overlap'
            if r.get('expectedRejects'):
                allowed={'ACCEPTED',*r['expectedRejects'].split(',')};functional=r['invariantsValid'] and r['unexpectedErrors']==0 and set(r['wholeRunOutcomes']).issubset(allowed)
                if 'DUPLICATE' in allowed:functional=functional and r['validation']['orders']==1 and r['wholeRunOutcomes'].get('DUPLICATE',0)==r['threads']-1
                if 'SOLD_OUT' in allowed:functional=functional and r['validation']['orders']==min(r['threads'],r['initialStock']) and r['wholeRunOutcomes'].get('SOLD_OUT',0)==r['threads']-r['initialStock']
                r['functionalCasePassed']=functional;runner.write_json(stage/'summary.json',r)
            runner.write_json(stage/'summary.json',r)
            results.append(r)
    rows=[]
    for r in results:
        rows.append({'stage':r['name'],'mode':r['mode'],'threads':r['threads'],'targetRps':r['targetRps'],'actualRequestRate':r['actualRequestRate'],'successfulRate':r['successfulRate'],'completionRate':r['completionRate'],'acceptanceP95Ms':r['acceptanceLatency']['p95Ms'],'serverCompletionP95Ms':r['serverCompletionLatency']['p95Ms'],'clientCompletionP95Ms':r['clientCompletionLatency']['p95Ms'],'queuePeak':r['queueReadyPeak'],'outstandingStart':r['outstandingStart'],'outstandingEnd':r['outstandingEnd'],'invariantsValid':r['invariantsValid'],'slo':r['meetsProposedSlo'],'expectedRejects':r['expectedRejects'],'functionalCasePassed':r.get('functionalCasePassed')})
        rows[-1]['environment']=r.get('environmentCondition','unclassified')
    with (root/'all-stages.csv').open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
    def best(mode,key):
        candidates=[r for r in results if r['mode']==mode and r['meetsProposedSlo'] and not r['name'].startswith('diagnostic')]
        return max(candidates,key=lambda r:r[key]) if candidates else None
    selected={'readThroughput':best('shop','successfulRate'),'readConcurrency':best('shop','threads'),'stableOrders':best('post','successfulRate'),'flowThroughput':best('flow','successfulRate'),'businessConcurrency':best('flow','threads')}
    confirmed=[r for r in results if r['mode']=='post' and r['meetsProposedSlo'] and r['seconds']>=180]
    if confirmed:selected['stableOrders']=max(confirmed,key=lambda r:r['successfulRate'])
    for mode,key in [('shop','readConcurrency'),('flow','businessConcurrency')]:
        confirmed=[r for r in results if r['mode']==mode and r['meetsProposedSlo'] and r['seconds']>=180]
        if confirmed:selected[key]=max(confirmed,key=lambda r:r['threads'])
    confirmed=[r for r in results if r['mode']=='shop' and r['meetsProposedSlo'] and r['seconds']>=180]
    selected['stableReadThroughput']=max(confirmed,key=lambda r:r['successfulRate']) if confirmed else selected['readThroughput']
    confirmed=[r for r in results if r['mode']=='flow' and r['meetsProposedSlo'] and r['seconds']>=180]
    if confirmed:selected['flowThroughput']=max(confirmed,key=lambda r:r['successfulRate'])
    runner.write_json(root/'selected-results.json',selected)
    if a.html:
        for name,label in [('stableReadThroughput','shop-read'),('stableOrders','submit-order'),('flowThroughput','submit-order'),('businessConcurrency','submit-order')]:
            r=selected[name]
            if not r:continue
            stage=root/r['name'];filtered=stage/'measurement-http.jtl';count=filtered_jtl(stage,filtered,label);output=root/('html-'+name)
            if (output/'index.html').exists() and (output/'statistics.json').exists():continue
            if output.exists() and any(output.iterdir()):raise RuntimeError('Incomplete HTML output, use a fresh report directory: '+str(output))
            with (root/('html-'+name+'.log')).open('w',encoding='utf-8') as log:
                subprocess.run(['java','-Xmx1024m','-jar',str(runner.JMETER/'bin/ApacheJMeter.jar'),'-g',str(filtered),'-o',str(output),'-Jsample_variables=outcome,orderId,userId,e2eMs','-Jjmeter.save.saveservice.url=false','-Jjmeter.save.saveservice.response_data=false','-Jjmeter.save.saveservice.response_message=true','-Jjmeter.save.saveservice.timestamp_format=ms','-Jjmeter.reportgenerator.statistic_window='+str(max(20000,count+1)),'-j',str(root/('html-'+name+'-jmeter.log'))],stdout=log,stderr=subprocess.STDOUT,check=True)
            print('HTML '+name+' samples='+str(count),flush=True)
    def value(v):return '—' if v is None else str(v)
    def ms(v):return '—' if v is None else str(v)+'ms'
    by_name={r['name']:r for r in results}
    lines=['# 2026-10-15 HTTP 接口容量测试报告','',
        '本次使用 JMeter 测试真实 HTTP 业务链路。项目没有脱离接口、延迟标准和负载模型的单一“极限 QPS”；下面分别给出热点读、稳定下单及持续业务并发的实测边界。',
        '', '## 核心结果','',
        '| 场景 | 环境 | 正式测量 | 线程 | 成功请求/秒 | 完成订单/秒 | 接口 P95 | 服务端完成 P95 | 客户端完成 P95 |',
        '|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for title,key in [('商户热点查询短测峰值','readThroughput'),('商户热点查询持续吞吐','stableReadThroughput'),('商户查询最高长测达标档位','readConcurrency'),('固定节拍下单最高长测达标档位','stableOrders'),('完成后再下单的最高长测吞吐','flowThroughput'),('下单流程最高长测达标线程档位','businessConcurrency')]:
        r=selected[key]
        if r:lines.append(f"| {title}（{r['name']}） | {r.get('environmentCondition','unclassified')} | {r['seconds']}秒 | {r['threads']} | {r['successfulRate']} | {value(r['completionRate']) if r['mode']!='shop' else '—'} | {ms(r['acceptanceLatency']['p95Ms'])} | {ms(r['serverCompletionLatency']['p95Ms'])} | {ms(r['clientCompletionLatency']['p95Ms'])} |")
    if background:
        lines += ['', '**环境变化：12:16:38 起本机出现新增游戏负载，12:21:45 实测内存占用 97%、可用内存约 0.87GiB。12:37 用户关闭游戏后确认进程退出，内存占用降至 72%、可用约 8.59GiB。受影响阶段标记为 additional-load-overlap；恢复后的独立复测标记为 recovered。主表保留各自环境标签，不将不同条件的测试混为同一上限。**',
            '', '此前 60 秒测试中，100 线程流程约 300 单/秒，400 线程流程约 254 单/秒、客户端完成 P95 约 1.82 秒达标。主表的持续容量结论采用资源恢复后的 180 秒复测，资源争用及短测成绩在后表中保留。']
    lines += ['', 'QPS 是每秒成功请求数，TPS 是每秒确认完成的订单数；P95 为 95% 请求不超过的耗时。HTTP 返回订单号表示受理，消费者完成数据库事务后才算订单完成，二者必须分开。轮询查询另计，不能加进下单 QPS。',
        '', '固定节拍下单不断按指定速率发新请求，不等待订单完成；闭环流程则由每个线程下单、每 250ms 查询状态、确认完成后换一个用户再次下单。两种模式的请求节奏、状态查询负载和批次形态不同，结果分别有效，不能合并为一个通用上限。',
        '', '最高达标线程档位表示该模型下的持续业务执行通道，不等于同时在线用户数、HTTP 在途请求数或数据库连接数。线程在等待轮询时没有持续占用 MySQL 连接。未测试的中间档位不作精确推断。',
        '', '## 测试条件与步骤','',
        '1. 单个 SpringBoot 测试实例运行于 8082。原 8081 应用保持运行，但不处理本轮测试流量。JMeter、测试应用、MySQL 和 RabbitMQ 的 Docker 容器共用本机，Redis 位于虚拟机；请求直达 127.0.0.1:8082，未经过 Nginx、公网或负载均衡。',
        '2. 从项目克隆表结构及索引，建立独立测试库，导入 100,000 名用户并预建登录会话。使用空闲 Redis 逻辑库和独立 RabbitMQ vhost。每档新建一张库存 100,000 的券；同一券中每次正常请求换新用户，保留一人一单约束。功能用例另设库存。',
        '3. 先做小流量联通、重复下单和售罄校验，再做阶梯发压。保持原有 Caffeine、日志、数据库连接池、MQ 持久化与确认配置；消费者按原配置从 2 个动态扩展到最多 8 个。限流对照单独记录，其余容量档位关闭下单限流以观察处理能力。',
        '4. 普通档位通常预热 15 秒、测量 60 秒；临界档位测量 180 秒。下单长测增加到 75 秒预热，让消费者完成动态扩容；每档实际时长见 stage.json。商户查询为匿名访问同一商户，Caffeine 开启，测的是热点缓存命中接口。登录、缓存未命中和混合业务不在本轮范围。',
        '5. 原生 HTTP Sampler 发请求，Groovy 断言解析业务结果。固定速率边界复测使用绝对节拍定时器，避免原生共享 Constant Throughput Timer 的毫秒舍入偏差；所有结论按实际发送量计算。库存核验、订单 ID 集合核验及服务端时间戳导出均在每档排空后进行。',
        '', '正式窗口按固定秒数计数，排除预热；同时保留全轮错误统计。订单延迟使用窗口内受理的订单，即使在窗口后才完成也计入延迟，避免把慢订单排除。服务端完成时间是消费者从事务方法返回后记录的确认时间；`dbProcessMillis` 包含状态维护等处理，不是纯 SQL 耗时。',
        '', '## 持续负载与失败边界','',
        '| 阶段 | 环境 | 测量秒数 | 线程 | 成功请求/秒 | 完成订单/秒 | 接口P95(ms) | 服务端完成P95(ms) | 客户端完成P95(ms) | 窗口异常数 | 全轮HTTP异常 | 积压起→末 | 达标 |',
        '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|']
    for r in results:
        if r['mode']=='burst':continue
        lines.append(f"| {r['name']} | {r.get('environmentCondition','unclassified')} | {r['seconds']} | {r['threads']} | {r['successfulRate']} | {value(r['completionRate'])} | {value(r['acceptanceLatency']['p95Ms'])} | {value(r['serverCompletionLatency']['p95Ms'])} | {value(r['clientCompletionLatency']['p95Ms'])} | {r['unexpectedErrors']} | {r['wholeRunTransportErrors']} | {r['outstandingStart']}→{r['outstandingEnd']} | {'是' if r['meetsProposedSlo'] else '否'} |")
    lines += ['', '“全轮”包含预热，正式窗口达标不表示冷启动或连接爬升阶段没有错误。原生限速目标 300 的一次测试实发约 333 次/秒，目标 150 实发约 143 次/秒；已按实际流量报告，并用精确节拍复测临界档位。过载档位待消息排空后才开始下一档。',
        '', '消费者动态扩容影响冷态结果：第一次每秒 240 次的 180 秒复测在仅预热 15 秒后开始，正式窗口仍经历消费者扩容及早期积压，完成 P95 约 9.84 秒；这轮失败保留。后续 75 秒预热的复测单独列出，不能用预热后的结果承诺冷启动表现。',
        '', '## 集中抢购与正确性','',
        '| 阶段 | 同时释放线程 | 受理订单 | 预期拒绝 | HTTP异常 | 连接预热失败 | 接口P95(ms) | 客户端完成P95(ms) | 容量/功能达标 |',
        '|---|---:|---:|---|---:|---:|---:|---:|---|']
    for r in results:
        if r['mode']!='burst':continue
        outcomes=r['wholeRunOutcomes'];rejected={k:v for k,v in outcomes.items() if k not in ('ACCEPTED','HTTP_ERROR')}
        flag=r.get('functionalCasePassed',r['meetsProposedSlo'])
        lines.append(f"| {r['name']} | {r['threads']} | {outcomes.get('ACCEPTED',0)} | {json.dumps(rejected,ensure_ascii=False)} | {outcomes.get('HTTP_ERROR',0)} | {r['connectionWarmupFailures']} | {value(r['acceptanceLatency']['p95Ms'])} | {value(r['clientCompletionLatency']['p95Ms'])} | {'是' if flag else '否'} |")
    lines += ['', '冷连接突发每轮使用新的 JMeter 进程；200～1,000 个线程同时首次连接时出现大量连接拒绝。连接预热组让每个线程先建立自己的持久 HTTP 连接，再同步下单；1,000 次请求全部成功受理并落库，但客户端完成 P95 约 4.35 秒，超过 2 秒标准。500 次预热突发 P95 约 2.06 秒也未达标，200 次约 0.82 秒达标。不能将“1,000 单最终成功”表述为“支持 1,000 持续并发且 2 秒内完成”。连接拒绝的精确根因尚未通过参数对照实验定位。',
        '', '重复下单用例为同一用户同时发 100 次：1 单成功、99 次重复拒绝；售罄复测为 500 名用户抢 100 库存：100 单成功、400 次售罄拒绝，无超卖。首次冷连接售罄轮出现传输错误，未覆盖完整拒绝路径，保留失败记录并以连接预热后的复测验证。突发波次的瞬时请求速率不能当作持续 QPS。完成延迟针对已受理订单，失败请求另计；失败档位中较低的完成延迟不能解释为更高容量。',
        '', '## 瓶颈证据','',
        '固定节拍每秒约 250 次下单时，HTTP 受理仍很快，但完成仅约 234 单/秒，积压从 16 增长到 927，服务端完成 P95 约 4.30 秒；实发约 333 次/秒时积压增至一万余笔。说明首先触及的是订单消费完成阶段，而不是 HTTP 返回订单号的速度。',
        '', '过载档位采集 8 份线程快照，在 56 个正在执行订单业务的消费者快照中，38 个位于等待同步 Redis 操作、10 个位于日志写出、7 个位于 MySQL 调用、1 个位于 MyBatis 构造 SQL。Redis 栈集中在 Pending 状态维护和 TTL 刷新；这些操作与 SQL 事务、成功日志共同占用最多 8 个消费者的处理时间。该证据是阻塞位置采样，不能当作精确耗时占比，也不能直接断言 Redis 服务端 CPU 或 MySQL 单行锁是唯一瓶颈。',
        '', '商户热点读每次命中 Caffeine 仍会输出 INFO 日志（ShopCacheService）。本轮保留这些逐请求日志，查询吞吐包含同步日志成本；尚未做关闭日志的对照实验，不能给出它造成的精确吞吐损失。',
        '', '可据此安排后续优化对照：先合并消费者中多次 Redis 状态读写，减少跨虚拟机往返，并降低逐单同步日志成本；保持成功判定和持久化语义不变后重跑相同 JMX，再判断是否需要增加消费者。每次状态轮询还会执行登录校验、Token 续期和状态读取；可对照验证降低轮询频率或使用退避间隔的效果。原有组件单测的 MySQL TPS、Redis 命令吞吐和 RabbitMQ 消息吞吐不等于这条完整链路的订单 TPS。',
        '', '## 判定、核验与记录','',
        '容量标准为本次测试约定：业务成功率 ≥99.9%，读接口 P95 ≤200ms，下单受理 P95 ≤300ms，服务端完成 P95 ≤2s；带轮询的业务并发还要求客户端完成 P95 ≤2s。固定节拍下单要求完成速度跟上受理速度，且末段积压无持续增长。闭环业务以完成延迟和失败率为主，待完成数受线程数量约束。',
        '', f"共完成 {len(results)} 个阶段。订单数量、用户唯一性、MySQL/Redis 库存、reservation、成功受理订单 ID 集合及队列排空核验：{sum(r['invariantsValid'] for r in results)}/{len(results)} 通过。通过数据一致性核验不代表通过容量或功能覆盖判定；传输失败和超时档位仍按失败报告。",'',
        'HTTP 在途峰值依据客户端请求起止时间估算，已修正 JMeter 毫秒精度的同线程舍入重叠；它包含网络等待，不是服务端活跃线程数。MySQL 行锁等全局状态及 Redis 实例级状态可能包含原实例背景活动，不能归因为单个测试券。',
        '', '运行目录中每档保留 test.jmx、stage.json、clock.json、samples.jtl、monitor.csv、orders.csv、validation.json、summary.json 和执行日志；all-stages.csv 为汇总。HTML 报告只包含相应正式窗口的目标 HTTP 接口，不含轮询和事务父样本。HTML 的 statistic_window 已扩大到覆盖全部筛选样本，避免默认最近 20,000 条的分位数窗口与全量汇总出现差异。Token 文件和 .env 不进入归档。']
    cleanup=root/'cleanup-validation.json'
    if cleanup.exists():lines += ['', '清理及原服务复核：`'+json.dumps(runner.read_json(cleanup),ensure_ascii=False)+'`。']
    report=HERE/'2026-10-15-http-capacity-report.md';report.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    if a.archive:
        destination=runner.MODULE.parent/'.codex_out'/('http-capacity-20261015-'+datetime.datetime.now().strftime('%H%M%S')+'.zip');destination.parent.mkdir(exist_ok=True)
        count=0
        with zipfile.ZipFile(destination,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
            for file in root.rglob('*'):
                if file.is_file() and 'tokens' not in file.name.lower() and file.suffix not in ('.env',):z.write(file,file.relative_to(root));count+=1
            for file in HERE.iterdir():
                if file.is_file() and file.suffix in ('.py','.java','.ps1','.md'):z.write(file,'harness/'+file.name);count+=1
            for name in ('users-100000.csv','generate-users.ps1','README.md'):
                file=runner.MODULE/'docs/jmeter/data'/name
                if file.exists():z.write(file,'fixtures/'+name);count+=1
        with zipfile.ZipFile(destination) as z:
            if any('tokens' in n.lower() or n.endswith('.env') for n in z.namelist()):raise RuntimeError('Sensitive file in archive')
        with report.open('a',encoding='utf-8') as f:f.write('\n原始记录归档：['+destination.name+']('+destination.as_posix()+')。\n')
        runner.write_json(root/'archive-manifest.json',{'archive':str(destination),'entries':count,'bytes':destination.stat().st_size,'tokensExcluded':True})
        print('ARCHIVE '+str(destination),flush=True)
    print('REPORT '+str(report),flush=True)

if __name__=='__main__':main()
