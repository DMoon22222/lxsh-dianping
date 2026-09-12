# 雪花 ID 生成器改造说明

## 改造目标

将订单 ID 从“时间戳 + Redis 每日自增值”改为 Hutool 雪花算法，生成 ID 时不再访问 Redis。RabbitMQ 消息结构、订单表主键和消费幂等逻辑保持不变。

## 修改内容

1. 删除 `RedisIdWorker`。
2. 新增 `SnowflakeIdGenerator`，封装 Hutool `Snowflake`。
3. 在 `application.yaml` 配置 `worker-id` 和 `datacenter-id`。
4. `VoucherOrderServiceImpl` 改为调用 `snowflakeIdGenerator.nextId()`。
5. 删除原来只打印 ID 的 Spring 集成测试。
6. 新增独立单元测试，自动验证递增性、并发唯一性、多节点隔离和配置边界。

## 配置

```yaml
hmdp:
  id-generator:
    worker-id: 1
    datacenter-id: 1
```

两个值的合法范围都是 `0-31`。同一个数据中心内，每个同时运行的应用实例必须使用不同的 `worker-id`。如果两个实例使用相同的 `(datacenter-id, worker-id)`，雪花算法不能保证它们之间不重复。

例如：

```text
实例 A：datacenter-id=1, worker-id=1
实例 B：datacenter-id=1, worker-id=2
实例 C：datacenter-id=2, worker-id=1
```

生产部署时可用环境变量覆盖 Spring 配置，例如：

```powershell
$env:HMDP_ID_GENERATOR_WORKER_ID='2'
$env:HMDP_ID_GENERATOR_DATACENTER_ID='1'
mvn spring-boot:run
```

Spring Boot 的 relaxed binding 会把以上环境变量映射到 `hmdp.id-generator.*`。

## 生成器完整代码

```java
package com.hmdp.utils;

import cn.hutool.core.lang.Snowflake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {

    private static final long MAX_WORKER_ID = 31L;
    private static final long MAX_DATACENTER_ID = 31L;

    private final Snowflake snowflake;

    public SnowflakeIdGenerator(
            @Value("${hmdp.id-generator.worker-id:1}") long workerId,
            @Value("${hmdp.id-generator.datacenter-id:1}") long datacenterId
    ) {
        validateId("worker-id", workerId, MAX_WORKER_ID);
        validateId(
                "datacenter-id",
                datacenterId,
                MAX_DATACENTER_ID
        );
        this.snowflake = new Snowflake(workerId, datacenterId);
    }

    public long nextId() {
        return snowflake.nextId();
    }

    private static void validateId(
            String name,
            long value,
            long maxValue
    ) {
        if (value < 0 || value > maxValue) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and " + maxValue
            );
        }
    }
}
```

订单业务调用变为：

```java
@Resource
private SnowflakeIdGenerator snowflakeIdGenerator;

Long orderId = snowflakeIdGenerator.nextId();
```

## 自动化测试

执行：

```powershell
mvn -o -Dtest=SnowflakeIdGeneratorTest test
```

测试覆盖：

- 连续生成 10,001 个 ID，全部为正数且严格递增。
- 32 个线程并发生成 64,000 个 ID，断言集合大小也是 64,000。
- 两个不同 `worker-id` 的生成器各生成 10,000 个 ID，断言 20,000 个 ID 无冲突。
- `worker-id` 或 `datacenter-id` 超出 `0-31` 时必须抛出异常。

全项目编译：

```powershell
mvn -o -DskipTests compile
```

## 业务联调测试

1. 启动 MySQL、Redis、RabbitMQ 和应用。
2. 准备一张 Redis 与 MySQL 都有库存的专用秒杀券。
3. 登录后调用 `POST /voucher-order/seckill/{voucherId}`。
4. 确认接口返回一个正数订单 ID。
5. 确认 RabbitMQ 消费者最终在 `tb_voucher_order.id` 写入同一个 ID。
6. 确认 `tb_seckill_voucher.stock` 减 1。
7. 确认 Redis 不再新增 `icr:order:*` 计数 key。
8. 重复投递相同订单消息，确认数据库唯一索引仍能阻止重复订单和重复扣库存。

雪花 ID 通常超过 JavaScript 的安全整数范围。前端若直接解析 JSON 数字可能丢失精度，后续应将订单 `Long` ID 统一序列化为字符串。
