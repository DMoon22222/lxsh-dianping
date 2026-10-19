# 2026-10-19 提交前单元测试验证

本次验证针对待提交的缓存降级、缓存失效 Outbox 及已有秒杀补偿、分布式 ID 代码。执行日期为 2026-10-19（Asia/Shanghai），结果为 **BUILD SUCCESS：15 个测试通过，失败 0，错误 0，跳过 0**。

## 环境与命令

- Maven 3.9.4，JDK 21.0.7，Windows。
- 在仓库内的 `hm-dianping` Maven 模块目录执行。
- 本组测试使用本地对象和 Mock，不要求连接 MySQL、Redis 或 RabbitMQ。

```powershell
mvn -B "-Dtest=ShopCacheServiceTest,ShopServiceImplTest,CacheInvalidatePublisherTest,CacheInvalidationOutboxTaskTest,SnowflakeIdGeneratorTest,SeckillRedisKeysTest,PendingOrderRepublishTaskTest" test
```

## 结果

| 测试类 | 用例数 | 结果 | 覆盖范围 |
| --- | ---: | --- | --- |
| `ShopCacheServiceTest` | 2 | 通过 | Redis 读写异常、Caffeine 读取异常时的查询降级 |
| `ShopServiceImplTest` | 2 | 通过 | 商户更新失败判定、成功更新登记 Outbox |
| `CacheInvalidatePublisherTest` | 2 | 通过 | 单步失效异常不阻断其他失效步骤，汇总异常供重试 |
| `CacheInvalidationOutboxTaskTest` | 2 | 通过 | 首次失效后的延迟阶段、失败后的重新调度 |
| `SnowflakeIdGeneratorTest` | 5 | 通过 | 配置绑定、递增、并发唯一性、节点隔离和参数校验 |
| `SeckillRedisKeysTest` | 1 | 通过 | 同券多 Key 使用一致的 Redis Cluster Hash Tag |
| `PendingOrderRepublishTaskTest` | 1 | 通过 | 重投耗尽时先调用预扣恢复，再登记最终失败 |
| **合计** | **15** | **全部通过** | |

测试中的异常日志来自主动模拟的故障场景，不表示用例执行失败。Maven 退出码为 0；本地详细结果位于 `target/surefire-reports/`，控制台记录保存为 `target/github-publish-unit-tests.log`。

提交资料检查同时通过：2 个 Python 脚本语法检查、5 个 PowerShell 脚本语法检查、3 个 XML/JMX 文件解析，以及本次变更文档中 39 个本地链接的目标检查。两份汇总 CSV 分别保留 38 和 50 个阶段，其一致性核验字段全部为真；5,000 和 100,000 条用户 CSV 均符合生成器的测试数据规则。

## 验证范围

本次未重新执行历史容量压测，也未运行会连接外部服务或准备、重置数据库数据的集成测试。本报告不代表已经完成 Redis 宕机、RabbitMQ 节点丢失或 MySQL 崩溃恢复演练。

历史容量测试的环境、负载模型、成功标准、失败档位和数据一致性核验分别见：

- [2026-10-14 中间件吞吐压测报告](jmeter/capacity/2026-10-14-capacity-report.md)
- [2026-10-15 HTTP 接口容量测试报告](jmeter/http-capacity/2026-10-15-http-capacity-report.md)
