# HTTP 接口容量压测

实测结果见 [2026-10-15 容量报告](2026-10-15-http-capacity-report.md)，各阶段统计见 [汇总 CSV](2026-10-15-all-stages.csv)。完整原始归档保留在原测试环境，未随 Git 提交。

本目录使用原生 JMeter HTTP Request、CSV Data Set Config、Constant Throughput Timer、Synchronizing Timer 和 Groovy 断言，测量真实 SpringBoot 下单链路。边界复测使用 `--precise` 启用 JSR223 纳秒绝对节拍定时器，避免原生 Constant Throughput Timer 在共享节拍下的毫秒舍入误差。所有阶段均按实际发送数量计算吞吐。应用业务代码未因压测修改。

测试应用使用端口 8082，独立 MySQL 测试库（从项目克隆全部表结构，复制商户资料并导入 100,000 个测试用户）、原 Redis 实例的空闲逻辑库、原 RabbitMQ 节点的独立虚拟主机。原应用端口 8081 不处理这些测试订单。压力机、测试应用、MySQL 和 Docker 共用本机资源，Redis 位于虚拟机，结果限定于此部署。

## 文件

- `HttpCapacitySupport.java`：隔离资源、用户及 Token、专用券、监控、订单时间戳导出和库存一致性校验。
- `start-test-app.ps1`：启动单个测试实例，除连接隔离资源、端口、实例标识和限流开关外，沿用应用原配置。
- `run-http-capacity.py`：生成每档 JMX、执行 JMeter、导出 JSON 统计。
- `report-results.py`：从原始记录重新统计，生成 Markdown、JMeter HTML 和排除 Token 的归档。
- `finish-test.ps1`：核验本轮进程及资源归属、全部订单与队列排空后，清理本轮隔离资源并复查原应用。

运行目录为 `target/http-capacity/20261015-session`。其中 `tokens.csv` 含真实测试登录 Token，不应加入报告或公开归档；每行格式为 `token,userId`，无表头。JTL 仅保存用户 ID、订单 ID、业务结果和完成耗时，不保存 Token。

## 计数方式

- `shop-read`：匿名查询商户 1，不携带 Token，保持 Caffeine 开启，属于热点读接口模型。
- `submit-order`：HTTP 下单成功并返回新订单号才计入成功受理 QPS。
- `order-status`：带 `voucherId` 查询订单状态，单独统计，不并入下单 QPS。
- `order-final`：端到端用例的完成校验。客户端完成耗时从实际 POST 发出开始，排除同步发压前的屏障等待，包含轮询等待。
- `order-flow`：JMeter 事务父样本，包含屏障、定时器和子样本，不能直接当作下单接口吞吐或响应时间。

持续负载排除预热，以固定 60 秒或临界档位 180 秒测量窗口计算吞吐，不用所有样本的 Total 吞吐。订单长测预热 75 秒，让原配置的消费者完成动态扩容；冷态扩容期间的失败测试也保留。各档实际时长见 stage.json。突发用例报告实际发出时间跨度、请求成功数和完成延迟；其短时请求速率不等于可持续 QPS。

`orders.csv` 导出已有 Pending 状态中的服务端时间。订单完成 TPS 按窗口内确认落库的记录计算；延迟按窗口内受理的订单计算，包含窗口结束后才完成的订单。`dbProcessMillis` 是项目已有的阶段耗时字段，包含消费者状态维护等操作，不能解释为纯 MySQL SQL 耗时。

并发线程表示压测执行通道。HTTP 在途峰值由请求起止时间估算，修正 CSV 毫秒精度导致的同线程连续请求 1ms 重叠；它包含客户端和网络等待，不等于服务端同时执行的线程数。数据库连接和 MQ 消费者数量另行观察。

`--warm-client` 会在 Setup 阶段初始化 HTTP 客户端，并让每个业务线程先查询一次商户，建立自己的 HTTP 连接后再参与集中抢购。这样可以区分新建 TCP 连接突发与复用已有连接的下单突发。连接预热请求单独记录，不计入下单 QPS；冷客户端初次初始化会影响冷突发用例的客户端耗时。

稳定队列判断使用窗口内完成/受理速度及最后约 30 秒积压的回归趋势，避免用两个瞬时快照的随机差异判定增长。闭环业务线程每次等待完成后才发下一单，待完成数量本身受线程数约束，主要通过完成延迟、错误率判断其是否达标。正式测量与全轮（含预热）的错误分别保存。

## 正确性与容量判定

每档使用新的测试券；同一券内每次正常下单取新的 Token。库存充足，数据耗尽或安全阈值触发时不能认定该档为完整容量测量。重复下单、售罄及限流用例按预期拒绝分类，不把拒绝数算成成功下单吞吐。

结束后核对：成功订单数量、唯一用户数、MySQL 库存扣减、Redis 库存及 reservation 数量一致；无重复用户订单，Pending/Failed 及主队列清空。仅在停止本次测试进程、全部消息处理完后，清理本次所有的隔离资源。

初步容量标准：正常负载成功率至少 99.9%，商户读 P95 ≤200ms，下单受理 P95 ≤300ms，服务端受理至订单完成 P95 ≤2s，且积压不持续增长。该标准是测试约定，不是既有业务 SLA。最高达标线程档位与最高达标 QPS 分开报告，不外推为在线用户数。

## 执行示例

先编译支持工具，执行 `inspect` 和 `prepare`，创建本轮专用 MQ 虚拟主机，再用 `start-test-app.ps1` 启动测试应用。所有凭据从本地 `.env` 读取，不写入命令或统计文件。

在项目模块目录执行，例如：

```powershell
python docs/jmeter/http-capacity/run-http-capacity.py --root target/http-capacity/20261015-session --name rate-100 --mode post --threads 100 --rps 100 --warmup 15 --seconds 60
python docs/jmeter/http-capacity/run-http-capacity.py --root target/http-capacity/20261015-session --name flow-100 --mode flow --threads 100 --warmup 15 --seconds 60
python docs/jmeter/http-capacity/run-http-capacity.py --root target/http-capacity/20261015-session --name burst-500 --mode burst --threads 500 --warmup 0 --seconds 60 --flow
```

不能原样覆盖已存在的阶段目录；每次复测使用新的阶段名称和测试券。JMeter HTML 报告应从按接口及正式窗口筛选的 JTL 生成。
