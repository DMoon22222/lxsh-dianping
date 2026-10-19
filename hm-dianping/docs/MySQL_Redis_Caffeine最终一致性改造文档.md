# MySQL、Redis、Caffeine 最终一致性改造文档

## 1. 改造目标

商户详情采用 `Caffeine -> Redis -> MySQL` 三级读取链路。MySQL 是唯一可信数据源，Redis 和 Caffeine 都是可丢弃、可重建的数据副本。

本次改造解决以下问题：

- MySQL 更新 0 行仍然返回成功。
- MySQL 提交后，Redis 删除、Caffeine 清理或 MQ 发送任一步骤失败会阻断后续步骤。
- 缓存失效任务只存在于进程内，应用崩溃后无法恢复。
- RabbitMQ 消费失败会立即无限重新入队。
- Redis 查询或回填失败会让已经成功的 MySQL 查询也返回失败。
- 更新与并发查询交错时，旧数据可能在第一次删除后重新写回 Redis。

本方案保证最终一致性，不承诺 MySQL、Redis、Caffeine 强一致。

## 2. 总体设计

更新链路采用 Transactional Outbox：

```text
客户端更新商户
    -> MySQL事务开始
       -> UPDATE tb_shop
       -> INSERT tb_cache_invalidate_outbox
    -> MySQL事务提交
    -> Outbox定时任务领取事件
       -> 删除Redis
       -> 清理当前实例Caffeine
       -> RabbitMQ Fanout广播并等待Publisher Confirm
    -> 事件进入延迟阶段
    -> 延迟后再次执行删除和广播
    -> 标记完成
```

三个失效动作彼此隔离。某一步失败时，其余步骤仍然执行；只要任一步失败，Outbox 事件就不会完成，后续会幂等重试全部动作。

## 3. 失败处理语义

### 3.1 MySQL 更新失败

- SQL 异常或事务提交失败：商户更新和 Outbox 事件一起回滚。
- `updateById` 返回 `false`：视为更新失败，不创建失效事件。
- Redis 和 Caffeine 保持原值，与未更新的 MySQL 一致。

### 3.2 Redis 删除失败

- 已提交的 MySQL 不回滚。
- 继续清理本机 Caffeine 并发送 MQ，避免故障扩散到后续步骤。
- Outbox 按指数退避重试；消费者也会幂等地再次删除 Redis。
- 重试耗尽后事件进入 `DEAD` 并输出 ERROR 日志，监控系统应据此告警；Redis TTL 是最后兜底。

### 3.3 Caffeine 清理失败

- 已提交的 MySQL 不回滚，Redis 删除和 MQ 广播继续执行。
- Outbox 或消费者重试本地失效。
- 其他实例仍通过 Fanout 消息清理自己的本地缓存。
- 本地短 TTL 和实例重启是最后兜底。

### 3.4 MQ 失败

- 发布端等待 Broker Confirm；NACK、超时或 Return 均视为失败，Outbox 不完成。
- 消费端使用有限次数、带退避的本地重试。
- 消费重试耗尽后，消息进入缓存失效停车队列，避免无限快速重入队；运维监控停车队列并告警。

## 4. Outbox 状态模型

`tb_cache_invalidate_outbox` 的核心字段：

| 字段 | 含义 |
|---|---|
| `stage` | `0` 首次失效，`1` 延迟二次失效 |
| `status` | `0` 待处理，`1` 处理中，`2` 完成，`3` 死亡 |
| `retry_count` | 当前阶段失败次数 |
| `next_retry_time` | 下次执行时间；处理中时表示租约到期时间 |
| `last_error` | 最近一次失败摘要 |

任务领取使用条件更新实现轻量级抢占。处理实例崩溃后，过期租约会恢复为待处理状态。重复处理是允许的，因为删除 Redis、删除 Caffeine 和广播失效消息都是幂等操作。

默认策略：

- 每秒扫描一次，每批 50 条。
- 处理租约 30 秒。
- 单阶段最多重试 8 次。
- 退避从 1 秒开始，指数增长，最大 60 秒。
- 首次成功后延迟 1 秒执行第二次失效。

## 5. 查询降级

```text
Caffeine命中 -> 直接返回
Caffeine异常/未命中 -> Redis
Redis异常/未命中 -> MySQL
MySQL成功 -> 尝试回填Redis和Caffeine
```

Redis 读取或回填失败只记录日志，不覆盖 MySQL 的成功结果。MySQL 失败仍然向上抛出，防止用不确定的缓存数据伪装成功。

Redis 整体故障时会增加数据库压力；生产环境仍需结合连接池隔离、限流、熔断和监控使用。

## 6. RabbitMQ 设计

- 缓存失效交换机仍采用 Fanout。
- 每个运行实例拥有独立队列，负责清理本实例 Caffeine。
- 发布使用独立 `RabbitTemplate`，避免与秒杀订单的 Confirm 回调相互覆盖。
- 消费者每次同时尝试 Redis 和 Caffeine，任一步失败都会触发重试。
- 重试耗尽的消息投递到 `hmdp.cache.invalidate.parking.queue`。

实例队列为非持久、独占、自动删除队列。实例离线时本地 Caffeine 同时消失，因此不需要为离线实例永久保存广播消息。

## 7. 数据库升级

全新环境直接执行 `src/main/resources/db/hmdp.sql`。

已有环境执行 `src/main/resources/db/cache_consistency_outbox.sql`，其内容如下：

```sql
CREATE TABLE `tb_cache_invalidate_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `cache_name` varchar(32) NOT NULL,
  `cache_key` varchar(64) NOT NULL,
  `stage` tinyint unsigned NOT NULL DEFAULT '0',
  `status` tinyint unsigned NOT NULL DEFAULT '0',
  `retry_count` int unsigned NOT NULL DEFAULT '0',
  `next_retry_time` datetime NOT NULL,
  `last_error` varchar(512) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_due` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

## 8. 验证清单

- MySQL 更新 0 行时不生成 Outbox 事件。
- Outbox 插入失败时商户更新回滚。
- Redis 删除失败时 Caffeine 清理与 MQ 发布仍会执行。
- Caffeine 清理失败时 Redis 删除与 MQ 发布仍会执行。
- MQ Confirm 失败时 Outbox 进入退避重试。
- 首次失效成功后能够执行延迟二次失效。
- 消费失败重试耗尽后进入停车队列。
- Redis GET/SET 失败时，MySQL 查询成功的数据仍可返回并写入 Caffeine。
- 所有缓存删除和重复消息均保持幂等。

## 9. 运维关注项

需要监控：

- `status=0` 的 Outbox 数量和最老事件年龄。
- `status=3` 的死亡事件数量。
- 缓存失效停车队列深度。
- Redis 删除失败率、MQ Confirm 超时率。
- Redis 降级期间的 MySQL QPS、慢查询和连接池使用率。

死亡事件修复故障原因后，可人工将其 `status`、`retry_count` 和 `next_retry_time` 重置为待处理状态重新执行。
