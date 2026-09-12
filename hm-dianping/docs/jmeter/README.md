# 秒杀订单端到端压测

`seckill-order-e2e.jmx` 测量的不是下单接口立即返回的响应时间，而是客户端观察到订单状态变为 `COMPLETED` 的时间。

## 前置条件

1. 启动 MySQL、Redis、RabbitMQ 和应用；应用使用异步下单：`hmdp.seckill.async-enabled=true`。
2. 为端到端测试关闭入口限流：`hmdp.rate-limit.enabled=false`。
3. 使用专用 `voucherId`，不要选日常开发数据。下方的准备命令会删除该券已有订单、重置该券库存，并创建/复用 `1399xxxxxxxx` 测试用户。

`tokens.csv` 不要写表头，内容示例：

```text
2b3f...
7cd1...
```

## 自动准备压测数据

以下命令只会在显式传入 `hmdp.load-test.prepare=true` 时执行。它会对指定券执行删除订单、重置 MySQL/Redis 库存、清理该券的 Pending 记录，并生成 `target/jmeter/tokens.csv`：

```powershell
mvn "-Dtest=SeckillLoadTestDataTest" "-Dhmdp.load-test.prepare=true" "-Dhmdp.load-test.voucher-id=11" "-Dhmdp.load-test.user-count=1000" "-Dhmdp.load-test.stock=1000" "-Dhmdp.load-test.token-file=target/jmeter/tokens.csv" test
```

示例中的 `voucher-id=11` 仅表示当前数据库中已有的秒杀券；请在执行前确认它是可清理的专用测试券。

## 执行

在项目模块目录执行：

```powershell
F:\hmdpcode\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat -n -t docs\jmeter\seckill-order-e2e.jmx -l target\seckill-e2e.jtl -e -o target\seckill-e2e-report -Jhost=localhost -Jport=8081 -JvoucherId=11 -Jthreads=1000 -JrampUpSeconds=20 -JtokensFile=F:\hmdpcode\hm-dianping\hm-dianping\target\jmeter\tokens.csv -JpollIntervalMs=100 -JstatusTimeoutMs=60000
```

`Transaction Controller` 的父样本“秒杀订单端到端完成”包含下单请求、轮询间隔和状态查询。它的 P50/P95/P99 是“客户端看到订单已完成”的端到端耗时，最多包含一个 `pollIntervalMs` 的轮询误差。

## 结果核验

压测完成后应确认：

```text
JMeter 中断言失败数为 0
RabbitMQ Ready = 0，Unacked = 0
ZCARD seckill:pending = 0
tb_voucher_order 中该 voucherId 的订单数 = 成功请求数
```

接口 `GET /voucher-order/status/{orderId}` 返回的 `data` 中还包含服务端时间点：

```text
acceptedAt           Redis Lua 成功并登记 Pending
consumeStartedAt     消费者开始处理
dbCommittedAt        MySQL 事务成功返回后
queueDelayMillis     acceptedAt → consumeStartedAt
dbProcessMillis      consumeStartedAt → dbCommittedAt
endToEndMillis       acceptedAt → dbCommittedAt
```

它们适合用于定位端到端延迟主要来自 MQ 排队还是数据库事务。所有时间都来自应用服务器时钟；多实例压测时应保证机器使用 NTP 同步时钟。

## 本机基准结果（2026-09-07）

在本机 MySQL、虚拟机 Redis、Docker RabbitMQ 的环境中，以 1,000 个测试用户、20 秒爬坡、100ms 轮询执行一次：

| 指标 | 结果 |
| --- | ---: |
| 下单请求成功率 | 1000 / 1000（100%） |
| 下单接口受理平均耗时 | 16.57ms |
| 端到端平均耗时 | 266.80ms |
| 端到端 P95 / P99 | 1424ms / 2124ms |

这组端到端指标从发起下单开始，到 `GET /voucher-order/status/{orderId}` 返回 `COMPLETED` 为止；因此包括 RabbitMQ 排队、消费者事务落库及至多 100ms 的轮询误差。压测时关闭了入口限流，并临时将 Tomcat 设为 `threads.max=500`、`accept-count=2000`、`max-connections=5000`，避免 Web 容器连接容量主导结果。
