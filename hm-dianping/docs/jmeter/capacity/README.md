# 中间件容量压测

实测结果见 [2026-10-14 压测报告](2026-10-14-capacity-report.md)，各阶段统计见 [汇总 CSV](2026-10-14-all-stages.csv)。完整原始归档保留在原测试环境，未随 Git 提交。

使用 JMeter 5.6.3 CLI 和 Java Request，直接测试 Redis、MySQL、RabbitMQ。
账号只从模块目录的 `.env` 读取，不写入 JMX、报告或日志。
测试创建独立 Redis Key、MySQL 数据库和 RabbitMQ 队列；核验后清理本轮数据。

## 场景与计数

| 场景 | 一次成功样本 | 负载 |
|---|---|---|
| redis-get | 一个 GET | 10,000 个 Key，1KiB 值，不使用 Pipeline |
| redis-set | 一个 SET EX | 同样的 Key 空间与值大小，TTL 3600 秒 |
| redis-lua | 原项目 seckill.lua 成功执行一次 | 唯一用户与订单，隔离 Key，包含库存、预占和 Pending 写入 |
| mysql-read | 一次主键查询 | 独立 InnoDB 表，10,000 行，每行 1KiB，热缓存读 |
| mysql-read-batch | 一组串行主键查询 | SQL 与 mysql-read 相同，每组计数 Batch 次，以降低 JTL 写入和 JMeter 样本管理开销 |
| mysql-txn-hot | 一次成功提交事务 | 按项目表结构插入订单并条件扣减同一库存行，保留唯一索引 |
| mysql-txn-spread | 一次成功提交事务 | 相同事务，分散到 64 个库存行 |
| rabbit-roundtrip | 一批消息全部获得 Publisher Confirm | 单个 durable classic 队列、持久化 JSON 消息、4 个消费者、逐条手动 ACK |

RabbitMQ 的吞吐单位是消息/秒：成功批次数乘以 Batch。
RabbitMQ 的样本延迟是整批 Confirm 耗时，不能当成单条消息延迟。
MQ 消费者不写 MySQL，结果反映该消息收发配置的能力。
Redis Lua 是一次业务脚本，不等于一条基础 Redis 命令。
MySQL 事务包含两个写 SQL 和 COMMIT，不等于基础 SQL QPS。
mysql-read-batch 不合并 SQL、不改变事务；只合并统计样本。其 QPS 为成功组数乘 Batch，延迟是整组耗时。

## 执行示例

在 Maven 模块目录执行：

```powershell
mvn -B "-DincludeScope=runtime" dependency:build-classpath "-Dmdep.outputFile=target/jdbc-connection-check/runtime-classpath.txt"
& ./docs/jmeter/capacity/run-capacity.ps1 -Scenario redis-get -Clients @(8,32,128) -Duration 25 -Warmup 5
& ./docs/jmeter/capacity/run-capacity.ps1 -Scenario mysql-txn-hot -Clients @(8,32,128) -Duration 25 -Warmup 5
& ./docs/jmeter/capacity/run-capacity.ps1 -Scenario rabbit-roundtrip -Clients @(1,2,4,8) -Batch 256 -Duration 25 -Warmup 5
```

每档保存 `samples.jtl`、`monitor.csv`、`validation.properties`、`summary.json` 和 JMeter 日志。
统计排除前 Warmup 秒，只计算测量窗口内开始并完成的成功样本。
每轮核对 Lua 库存与预占数量、数据库事务与订单行数、MQ Confirm/ACK 与剩余消息数。
Redis 内存超过 1GiB 时停止当前档位，保护开发虚拟机。

`redis-crosscheck.ps1` 使用本机已有的 Redis Docker 镜像运行官方 redis-benchmark。
它只读取本轮创建的 1KiB 专用 Key，并对比无 Pipeline 与 Pipeline 模式；Docker 客户端网络路径和 Windows JMeter 路径不同，需分开报告。

这些结果描述当前部署、数据和客户端设置下的实测容量；负载进入平台期后仍需重复与持续验证，不能据此声称中间件具有固定的理论上限。
