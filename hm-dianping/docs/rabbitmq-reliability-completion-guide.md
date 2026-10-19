# RabbitMQ 秒杀下单可靠性功能补全文档

> 目标：在当前 `hm-dianping` 项目的 RabbitMQ 秒杀下单基础上，补齐“Redis 预扣成功，但消息没有可靠进入 MQ / 没有可靠创建订单”的补偿闭环。本文只写 RabbitMQ 相关补全方案，便于人工按步骤复现。

## 1. 当前实现结论

先给结论：方案文档里 RabbitMQ 第一阶段的基础链路已经实现了一部分，但还没有实现完整可靠投递补偿。

### 1.1 已经实现的功能

| 功能点 | 当前状态 | 代码位置 |
|---|---|---|
| 引入 `spring-boot-starter-amqp` | 已实现 | `pom.xml` |
| RabbitMQ 连接配置 | 已实现 | `src/main/resources/application.yaml` |
| `publisher-confirm-type: correlated` | 已配置 | `application.yaml` |
| `publisher-returns: true` + `mandatory: true` | 已配置 | `application.yaml` |
| Direct Exchange + 主订单队列 | 已实现 | `RabbitMqConfig` |
| 主队列持久化 | 已实现 | `QueueBuilder.durable(ORDER_QUEUE)` |
| TTL 重试队列 | 已实现 | `ORDER_RETRY_QUEUE`，TTL 5 秒 |
| 最终失败队列 | 已实现 | `ORDER_FAILED_QUEUE` |
| JSON 消息转换器 | 已实现 | `Jackson2JsonMessageConverter` |
| 订单消息 DTO | 已实现 | `VoucherOrderMessage` |
| 生产者绑定 ConfirmCallback | 已实现 | `VoucherOrderProducer#confirm` |
| 生产者绑定 ReturnCallback | 已实现 | `VoucherOrderProducer#returnedMessage` |
| 消费者手动 ACK | 已实现 | `VoucherOrderConsumer#listenVoucherOrder` |
| 数据库事务成功后 ACK | 已实现 | `createVouchOrder()` 成功后 `basicAck` |
| 重复消费幂等 | 基本实现 | 数据库主键 + `uk_user_voucher` 唯一索引 |
| 消费失败有限重试 | 已实现 | `x-death` 计数 + `MAX_RETRY_COUNT = 3` |

### 1.2 尚未实现或不完整的功能

| 缺口 | 当前表现 | 风险 |
|---|---|---|
| Lua 没有记录 PENDING 消息 | `seckill.lua` 只扣 Redis 库存并记录用户 | 应用在 Lua 成功后、调用 RabbitMQ 前宕机，会出现 Redis 少库存但 MQ / MySQL 都没有订单 |
| 订单 ID 在 Lua 之后生成 | 当前 `seckillVoucher()` 先执行 Lua，再生成 `orderId` | Lua 阶段无法原子记录待发送订单 |
| Confirm ACK 没有清理 PENDING 状态 | 当前只是日志 | 无法区分哪些预扣订单已进入 Broker |
| Confirm NACK 没有持久化失败状态 | 当前只是日志 | Broker 没收到消息时没有自动重投依据 |
| Return 没有退回补偿逻辑 | 当前只是日志 | Exchange 收到但无法路由到 Queue 时，Redis 预扣不会恢复或重投 |
| `convertAndSend()` 同步异常没有落补偿 | 当前接口返回失败 | 消息可能没发出，也可能发出了但确认丢失，状态不可恢复 |
| 没有 PENDING 扫描重投任务 | 当前无定时任务 | 超时待投递消息不会自动恢复 |
| 失败队列没有消费者 | 当前只把消息送到失败队列 | 失败消息不会形成业务状态、库存修复或人工处理记录 |
| 转发到失败队列后立刻 ACK 原消息 | 当前 `convertAndSend()` 不抛异常就 ACK | 极端情况下失败队列发送未被 Broker Confirm，但原消息已 ACK |

## 2. 补全后的目标链路

推荐补全后的主链路：

```text
用户请求秒杀
  -> Java 先生成 orderId
  -> Redis Lua 原子判断库存和一人一单
  -> Redis Lua 预扣库存、记录用户、写入 PENDING
  -> Java 发送 RabbitMQ 消息
  -> Confirm ACK：标记 SENT / 清理待重投集合
  -> Confirm NACK：标记 BROKER_NACK，进入待重投集合
  -> Return：标记 ROUTE_FAILED，进入待重投集合
  -> Consumer 写 MySQL 订单并扣 MySQL 库存
  -> DB 事务成功后 ACK
  -> Consumer 清理 PENDING 残留
```

异常补偿链路：

```text
PendingOrderRepublishTask 定时扫描 seckill:pending
  -> 取出超时 orderId
  -> 先查 MySQL 是否已有订单
  -> 已有订单：清理 PENDING
  -> 没有订单：按 retryCount 和 nextRetryTime 重发 MQ
  -> 超过最大重发次数：转入 FAILED，等待失败队列处理或人工处理
```

失败队列链路：

```text
Consumer 连续失败超过上限
  -> 消息进入 ORDER_FAILED_QUEUE
  -> FailedConsumer 查询 DB
  -> DB 已有订单：清理 PENDING，ACK
  -> DB 没有订单：标记 CONSUME_FAILED
  -> 可选择安全回滚 Redis 预扣库存和一人一单标记
```

## 3. Redis Key 设计

沿用当前 Redis 秒杀设计，新增 PENDING 相关 Key。

```text
seckill:stock:{voucherId}
  String
  秒杀库存。

seckill:order:{voucherId}
  Set
  已参与秒杀的 userId 集合。

seckill:pending
  ZSet
  member = orderId
  score = nextRetryTimeMillis
  用于定时扫描待补偿订单。

seckill:pending:data:{orderId}
  Hash
  保存待发送消息的完整必要字段。

seckill:pending:failed
  ZSet
  member = orderId
  score = failedTimeMillis
  保存最终失败订单，方便人工排查。

seckill:restore:{orderId}
  String
  Redis 库存回滚幂等标记，防止失败队列重复消费导致库存多加。
```

`seckill:pending:data:{orderId}` 推荐字段：

```text
orderId       订单 ID
userId        用户 ID
voucherId     优惠券 ID
status        PENDING / SENDING / SENT / BROKER_NACK / ROUTE_FAILED / REPUBLISHING / CONSUME_FAILED / FAILED
retryCount    重投次数
createTime    创建时间戳，毫秒
nextRetryTime 下次重试时间戳，毫秒
lastError     最近一次错误
```

## 4. 第一步：修改 Lua，原子记录 PENDING

当前 `seckill.lua` 的问题是：它只做了 Redis 预扣和一人一单记录，没有记录订单消息。因此要把 `orderId`、`voucherId`、`timestamp` 从 Java 传入 Lua。

替换 `src/main/resources/seckill.lua`：

```lua
-- KEYS[1]: seckill:stock:{voucherId}
-- KEYS[2]: seckill:order:{voucherId}
-- KEYS[3]: seckill:pending
-- KEYS[4]: seckill:pending:data:{orderId}
-- ARGV[1]: userId
-- ARGV[2]: voucherId
-- ARGV[3]: orderId
-- ARGV[4]: nowMillis

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local pendingKey = KEYS[3]
local pendingDataKey = KEYS[4]

local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local nowMillis = ARGV[4]

local stock = redis.call('GET', stockKey)
if stock == false or tonumber(stock) <= 0 then
    return 1
end

if redis.call('SISMEMBER', orderKey, userId) == 1 then
    return 2
end

redis.call('INCRBY', stockKey, -1)
redis.call('SADD', orderKey, userId)

redis.call('ZADD', pendingKey, nowMillis, orderId)
redis.call('HSET', pendingDataKey,
    'orderId', orderId,
    'userId', userId,
    'voucherId', voucherId,
    'status', 'PENDING',
    'retryCount', '0',
    'createTime', nowMillis,
    'nextRetryTime', nowMillis,
    'lastError', '')
redis.call('EXPIRE', pendingDataKey, 86400)

return 0
```

作用：

- 把“库存预扣”和“待发送消息记录”放在同一个 Lua 脚本里，保证原子性。
- 即使 Java 进程在 Lua 成功后立刻宕机，也能靠 `seckill:pending` 找回这笔待发送订单。
- 后续 Confirm NACK、Return、发送异常都只需要更新这个 PENDING 状态。

## 5. 第二步：调整下单入口，先生成 orderId 再执行 Lua

当前 `VoucherOrderServiceImpl#seckillVoucher()` 是先执行 Lua，再生成订单 ID。需要调换顺序。

参考修改：

```java
@Override
public Result seckillVoucher(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    Long orderId = snowflakeIdGenerator.nextId();
    long now = System.currentTimeMillis();

    Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Arrays.asList(
                    "seckill:stock:" + voucherId,
                    "seckill:order:" + voucherId,
                    "seckill:pending",
                    "seckill:pending:data:" + orderId
            ),
            userId.toString(),
            voucherId.toString(),
            orderId.toString(),
            String.valueOf(now)
    );

    if (result == null) {
        return Result.fail("秒杀服务异常");
    }

    int code = result.intValue();
    if (code != 0) {
        return Result.fail(code == 1 ? "库存不足" : "不能重复下单");
    }

    VoucherOrderMessage orderMessage =
            new VoucherOrderMessage(orderId, userId, voucherId);

    try {
        voucherOrderProducer.sendOrderMessage(orderMessage);
    } catch (AmqpException e) {
        pendingOrderService.markSendFailed(
                orderId,
                "convertAndSend exception: " + e.getMessage()
        );
        log.error("发送秒杀订单消息异常，orderId={}", orderId, e);
        return Result.ok(orderId);
    }

    return Result.ok(orderId);
}
```

注意这里发送异常时建议仍返回 `Result.ok(orderId)`，原因是 Redis 预扣已经成功，PENDING 也已经记录。用户请求已经被系统接收，后续由补偿任务重投。返回失败容易让用户重复点击，但 Redis 一人一单又会拦截，体验反而更混乱。

需要在 `VoucherOrderServiceImpl` 新增依赖：

```java
@Resource
private PendingOrderService pendingOrderService;
```

## 6. 第三步：新增 PENDING 服务

新增文件：

```text
src/main/java/com/hmdp/mq/PendingOrderService.java
```

示例代码：

```java
package com.hmdp.mq;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
public class PendingOrderService {

    public static final String PENDING_KEY = "seckill:pending";
    public static final String FAILED_KEY = "seckill:pending:failed";
    public static final String DATA_KEY_PREFIX = "seckill:pending:data:";
    private static final long DATA_TTL_SECONDS = 86400;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void markSending(Long orderId) {
        String dataKey = dataKey(orderId);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "SENDING");
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    public void markSendSuccess(Long orderId) {
        String dataKey = dataKey(orderId);
        Object status = stringRedisTemplate.opsForHash().get(dataKey, "status");

        if ("ROUTE_FAILED".equals(status) || "BROKER_NACK".equals(status)) {
            return;
        }

        stringRedisTemplate.opsForZSet().remove(PENDING_KEY, orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "status", "SENT");
        stringRedisTemplate.opsForHash().put(dataKey, "sentTime", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    public void markSendFailed(Long orderId, String reason) {
        long now = System.currentTimeMillis();
        String dataKey = dataKey(orderId);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "BROKER_NACK");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "nextRetryTime", String.valueOf(now + 5000));
        stringRedisTemplate.opsForZSet().add(PENDING_KEY, orderId.toString(), now + 5000);
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    public void markRouteFailed(VoucherOrderMessage orderMessage, String reason) {
        long now = System.currentTimeMillis();
        Long orderId = orderMessage.getOrderId();
        String dataKey = dataKey(orderId);

        stringRedisTemplate.opsForHash().put(dataKey, "orderId", orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "userId", orderMessage.getUserId().toString());
        stringRedisTemplate.opsForHash().put(dataKey, "voucherId", orderMessage.getVoucherId().toString());
        stringRedisTemplate.opsForHash().put(dataKey, "status", "ROUTE_FAILED");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "nextRetryTime", String.valueOf(now + 5000));
        stringRedisTemplate.opsForZSet().add(PENDING_KEY, orderId.toString(), now + 5000);
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    public void beforeRepublish(Long orderId) {
        String dataKey = dataKey(orderId);
        Long retryCount = stringRedisTemplate.opsForHash().increment(dataKey, "retryCount", 1);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "REPUBLISHING");
        stringRedisTemplate.opsForHash().put(dataKey, "lastRetryTime", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().put(dataKey, "retryCount", String.valueOf(retryCount));
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    public void markFailed(Long orderId, String reason) {
        long now = System.currentTimeMillis();
        String dataKey = dataKey(orderId);
        stringRedisTemplate.opsForZSet().remove(PENDING_KEY, orderId.toString());
        stringRedisTemplate.opsForZSet().add(FAILED_KEY, orderId.toString(), now);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "FAILED");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "failedTime", String.valueOf(now));
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    public void removePending(Long orderId) {
        stringRedisTemplate.opsForZSet().remove(PENDING_KEY, orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey(orderId), "status", "ORDER_CREATED");
        stringRedisTemplate.expire(dataKey(orderId), Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    public Set<String> listDueOrderIds(long now, int limit) {
        return stringRedisTemplate.opsForZSet()
                .rangeByScore(PENDING_KEY, 0, now, 0, limit);
    }

    public Map<Object, Object> getData(Long orderId) {
        return stringRedisTemplate.opsForHash().entries(dataKey(orderId));
    }

    public String dataKey(Long orderId) {
        return DATA_KEY_PREFIX + orderId;
    }

    private String safe(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }
}
```

说明：

- `markSendSuccess()` 里要注意 Return 和 Confirm 的异步顺序。Return 表示不可路由，Confirm ACK 只表示 Broker 收到消息。如果状态已经是 `ROUTE_FAILED`，不能被 Confirm ACK 覆盖掉。
- 更严谨的做法是把 `markSendSuccess()` 改成 Redis Lua 原子脚本，避免并发覆盖。单机练习项目中，上面写法已经足够复现思路。

## 7. 第四步：补齐 Producer 的 Confirm / Return 逻辑

当前 `VoucherOrderProducer` 的 Confirm NACK 和 Return 只打日志。需要改为落状态。

参考代码：

```java
@Component
@Slf4j
public class VoucherOrderProducer
        implements RabbitTemplate.ConfirmCallback,
        RabbitTemplate.ReturnCallback {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private PendingOrderService pendingOrderService;

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnCallback(this);
    }

    public void sendOrderMessage(VoucherOrderMessage orderMessage) {
        String messageId = orderMessage.getOrderId().toString();
        pendingOrderService.markSending(orderMessage.getOrderId());

        CorrelationData correlationData = new CorrelationData(messageId);

        rabbitTemplate.convertAndSend(
                ORDER_EXCHANGE,
                ORDER_ROUTING_KEY,
                orderMessage,
                message -> {
                    message.getMessageProperties()
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(messageId);
                    return message;
                },
                correlationData
        );
    }

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            log.error("RabbitMQ Confirm 回调缺少 CorrelationData，ack={}，cause={}", ack, cause);
            return;
        }

        Long orderId = Long.valueOf(correlationData.getId());

        if (ack) {
            pendingOrderService.markSendSuccess(orderId);
            log.debug("RabbitMQ Broker 已接收订单消息，orderId={}", orderId);
            return;
        }

        pendingOrderService.markSendFailed(orderId, cause);
        log.error("RabbitMQ Broker 接收订单消息失败，orderId={}，cause={}", orderId, cause);
    }

    @Override
    public void returnedMessage(
            Message message,
            int replyCode,
            String replyText,
            String exchange,
            String routingKey
    ) {
        Object converted = rabbitTemplate.getMessageConverter().fromMessage(message);
        if (!(converted instanceof VoucherOrderMessage)) {
            log.error("RabbitMQ Return 消息反序列化失败，replyCode={}，replyText={}，message={}",
                    replyCode, replyText, message);
            return;
        }

        VoucherOrderMessage orderMessage = (VoucherOrderMessage) converted;
        String reason = "return replyCode=" + replyCode
                + ", replyText=" + replyText
                + ", exchange=" + exchange
                + ", routingKey=" + routingKey;

        pendingOrderService.markRouteFailed(orderMessage, reason);
        log.error("RabbitMQ 订单消息无法路由，orderId={}，{}", orderMessage.getOrderId(), reason);
    }
}
```

作用：

- Confirm ACK：说明 Broker 接收成功，待投递记录可以从 `seckill:pending` 移除。
- Confirm NACK：说明 Broker 未接收，必须保留 PENDING 并等待定时重投。
- Return：说明消息到了 Exchange，但没有匹配 Queue，也必须保留 PENDING 并等待重投。

## 8. 第五步：新增定时重投任务

需要开启 Spring 定时任务。

修改启动类：

```java
@SpringBootApplication
@EnableScheduling
public class HmDianPingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }
}
```

新增文件：

```text
src/main/java/com/hmdp/mq/PendingOrderRepublishTask.java
```

示例代码：

```java
package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class PendingOrderRepublishTask {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_REPUBLISH_COUNT = 5;

    @Resource
    private PendingOrderService pendingOrderService;

    @Resource
    private VoucherOrderProducer voucherOrderProducer;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Scheduled(fixedDelay = 10000)
    public void republishPendingOrders() {
        long now = System.currentTimeMillis();
        Set<String> orderIds = pendingOrderService.listDueOrderIds(now, BATCH_SIZE);
        if (CollectionUtils.isEmpty(orderIds)) {
            return;
        }

        for (String orderIdText : orderIds) {
            Long orderId = Long.valueOf(orderIdText);
            try {
                republishOne(orderId);
            } catch (Exception e) {
                log.error("补偿重投订单消息异常，orderId={}", orderId, e);
            }
        }
    }

    private void republishOne(Long orderId) {
        boolean exists = voucherOrderService.lambdaQuery()
                .eq(com.hmdp.entity.VoucherOrder::getId, orderId)
                .count() > 0;

        if (exists) {
            pendingOrderService.removePending(orderId);
            log.info("PENDING 订单已在数据库存在，清理待重投记录，orderId={}", orderId);
            return;
        }

        Map<Object, Object> data = pendingOrderService.getData(orderId);
        if (data == null || data.isEmpty()) {
            pendingOrderService.markFailed(orderId, "pending data missing");
            return;
        }

        int retryCount = parseInt(data.get("retryCount"));
        if (retryCount >= MAX_REPUBLISH_COUNT) {
            pendingOrderService.markFailed(orderId, "republish retry exhausted");
            log.error("订单消息重投超过上限，转入 FAILED，orderId={}", orderId);
            return;
        }

        Long userId = Long.valueOf(String.valueOf(data.get("userId")));
        Long voucherId = Long.valueOf(String.valueOf(data.get("voucherId")));

        pendingOrderService.beforeRepublish(orderId);
        voucherOrderProducer.sendOrderMessage(new VoucherOrderMessage(orderId, userId, voucherId));

        log.warn("已重投 PENDING 订单消息，orderId={}，retryCount={}", orderId, retryCount + 1);
    }

    private int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
```

设计要点：

- 重投前先查 MySQL，这是必须的。因为消息可能已经被消费成功，只是 Confirm / ACK / 清理状态丢了。
- 重投允许产生重复消息，消费者依靠数据库唯一索引兜底幂等。
- 重投不要无限进行，超过上限后转入 `seckill:pending:failed`，避免任务永远刷同一条问题消息。

## 9. 第六步：Consumer 成功后清理 PENDING 残留

当前消费者只在 DB 成功后 ACK，没有清理 PENDING。补偿任务虽然可以通过查 DB 清理，但消费者成功时主动清理更直接。

在 `VoucherOrderConsumer` 注入：

```java
@Resource
private PendingOrderService pendingOrderService;
```

在 `createVouchOrder()` 成功后、`basicAck()` 前后都可以清理。推荐事务方法返回成功后先清理，再 ACK：

```java
voucherOrderService.createVouchOrder(voucherOrder);
pendingOrderService.removePending(orderMessage.getOrderId());
channel.basicAck(deliveryTag, false);
```

在 `DuplicateKeyException` 分支也清理：

```java
pendingOrderService.removePending(orderMessage.getOrderId());
channel.basicAck(deliveryTag, false);
```

作用：

- 如果生产者 Confirm ACK 没触发，消费者成功后仍能清理 PENDING。
- 如果重复消息到达，数据库唯一索引拦截后也能把残留状态清掉。

## 10. 第七步：失败队列消费者和 Redis 预扣恢复

当前消费者超过重试次数后把消息发到 `ORDER_FAILED_QUEUE`，但没有失败队列消费者。建议新增失败队列消费者，至少把失败状态落到 Redis，便于人工排查。

### 10.1 新增恢复 Lua

新增文件：

```text
src/main/resources/seckill_restore.lua
```

内容：

```lua
-- KEYS[1]: seckill:stock:{voucherId}
-- KEYS[2]: seckill:order:{voucherId}
-- KEYS[3]: seckill:restore:{orderId}
-- KEYS[4]: seckill:pending
-- ARGV[1]: userId
-- ARGV[2]: orderId
-- ARGV[3]: nowMillis

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local restoreKey = KEYS[3]
local pendingKey = KEYS[4]

local userId = ARGV[1]
local orderId = ARGV[2]
local nowMillis = ARGV[3]

if redis.call('SETNX', restoreKey, nowMillis) == 0 then
    return 0
end

redis.call('EXPIRE', restoreKey, 86400)

if redis.call('SISMEMBER', orderKey, userId) == 1 then
    redis.call('INCRBY', stockKey, 1)
    redis.call('SREM', orderKey, userId)
end

redis.call('ZREM', pendingKey, orderId)

return 1
```

作用：

- 失败队列消息可能重复消费，所以使用 `seckill:restore:{orderId}` 保证库存最多恢复一次。
- 只有用户仍在 `seckill:order:{voucherId}` 集合中时才加库存，避免多加。

### 10.2 新增失败队列消费者

新增文件：

```text
src/main/java/com/hmdp/mq/VoucherOrderFailedConsumer.java
```

示例代码：

```java
package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Arrays;

import static com.hmdp.mq.RabbitMqConstants.ORDER_FAILED_QUEUE;

@Component
@Slf4j
public class VoucherOrderFailedConsumer {

    private static final DefaultRedisScript<Long> RESTORE_SCRIPT;

    static {
        RESTORE_SCRIPT = new DefaultRedisScript<>();
        RESTORE_SCRIPT.setLocation(new ClassPathResource("seckill_restore.lua"));
        RESTORE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private PendingOrderService pendingOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = ORDER_FAILED_QUEUE)
    public void listenFailedOrder(
            VoucherOrderMessage orderMessage,
            Message message,
            Channel channel
    ) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long orderId = orderMessage.getOrderId();

        try {
            boolean exists = voucherOrderService.lambdaQuery()
                    .eq(VoucherOrder::getId, orderId)
                    .count() > 0;

            if (exists) {
                pendingOrderService.removePending(orderId);
                channel.basicAck(tag, false);
                log.warn("失败队列订单已在数据库存在，直接确认，orderId={}", orderId);
                return;
            }

            Long restored = stringRedisTemplate.execute(
                    RESTORE_SCRIPT,
                    Arrays.asList(
                            "seckill:stock:" + orderMessage.getVoucherId(),
                            "seckill:order:" + orderMessage.getVoucherId(),
                            "seckill:restore:" + orderId,
                            PendingOrderService.PENDING_KEY
                    ),
                    orderMessage.getUserId().toString(),
                    orderId.toString(),
                    String.valueOf(System.currentTimeMillis())
            );

            pendingOrderService.markFailed(orderId, "consume failed, redis restored=" + restored);
            channel.basicAck(tag, false);
            log.error("订单进入失败队列并已处理，orderId={}，restored={}", orderId, restored);
        } catch (Exception e) {
            channel.basicNack(tag, false, true);
            log.error("处理订单失败队列异常，orderId={}", orderId, e);
        }
    }
}
```

如果你不希望失败队列自动恢复 Redis 库存，可以先去掉 `seckill_restore.lua` 执行逻辑，只保留 `pendingOrderService.markFailed()` 和日志。面试表达时要说清楚：自动恢复适合“系统失败导致订单没有创建”的场景，不适合“用户取消但业务规定仍算参与过”的场景。

## 11. 第八步：修正“转发失败队列后立刻 ACK”的极端风险

当前 `VoucherOrderConsumer` 在超过最大重试次数后：

```java
rabbitTemplate.convertAndSend(ORDER_FAILED_EXCHANGE, ORDER_FAILED_ROUTING_KEY, orderMessage);
channel.basicAck(deliveryTag, false);
```

这存在一个极端窗口：`convertAndSend()` 不抛异常不等于失败队列消息已经被 Broker Confirm。严格方案应该让主队列通过 DLX 自动进入最终失败队列，而不是消费者手动转发后 ACK。

更推荐的队列设计：

```text
主队列消费失败 basicReject(requeue=false)
  -> 死信到 retry.exchange / retry.queue
  -> TTL 到期后死信回主队列
  -> x-death 次数超过上限后，消费者 basicReject(requeue=false)
  -> 直接死信到 failed.exchange / failed.queue
```

但 RabbitMQ 一个队列只能配置一个 dead-letter-exchange。你当前主队列已经死信到重试交换机，所以“超过重试上限后直接死信到 failed.exchange”不能只靠同一个主队列配置完成。可选方案：

方案 A，保留当前代码：

- 简单，适合练习项目。
- 风险是发送失败队列后未等待 Confirm。
- 可以接受，但文档里要如实说明。

方案 B，新增一个 `OrderFailedPublisher`，使用同步等待 Confirm：

- 发送失败队列消息时使用 `CorrelationData#getFuture()` 等待确认。
- Confirm ACK 后再 ACK 原消息。
- Confirm NACK 或超时则 `basicNack(requeue=true)` 原消息。

示例：

```java
CorrelationData cd = new CorrelationData(orderMessage.getOrderId() + ":failed");
rabbitTemplate.convertAndSend(
        ORDER_FAILED_EXCHANGE,
        ORDER_FAILED_ROUTING_KEY,
        orderMessage,
        cd
);

CorrelationData.Confirm confirm = cd.getFuture().get(3, TimeUnit.SECONDS);
if (confirm.isAck()) {
    channel.basicAck(deliveryTag, false);
} else {
    channel.basicNack(deliveryTag, false, true);
}
```

Spring Boot 2.7.x 对应的 Spring AMQP 支持 `CorrelationData#getFuture()`。如果本地版本编译不过，就保留方案 A，并把失败队列发送失败时 `basicNack(requeue=true)` 的保护逻辑写清楚。

## 12. 第九步：验收用例

### 12.1 正常下单

期望：

- Redis `seckill:stock:{voucherId}` 减 1。
- Redis `seckill:order:{voucherId}` 有 userId。
- RabbitMQ 主队列收到消息。
- MySQL `tb_voucher_order` 有订单。
- `seckill:pending` 中没有该 orderId。

检查命令示例：

```text
ZRANK seckill:pending {orderId}
HGETALL seckill:pending:data:{orderId}
SELECT * FROM tb_voucher_order WHERE id = {orderId};
```

### 12.2 Confirm NACK

模拟方式：

- 临时关闭 RabbitMQ。
- 请求秒杀接口。
- Lua 成功后发送阶段异常或 Confirm NACK。

期望：

- `seckill:pending` 保留 orderId。
- `seckill:pending:data:{orderId}` 状态为 `BROKER_NACK` 或保留 `PENDING`。
- RabbitMQ 恢复后，定时任务重投。
- 最终 MySQL 创建订单，PENDING 被清理。

### 12.3 Return 不可路由

模拟方式：

- 临时把 `ORDER_ROUTING_KEY` 改成错误值。
- 启动项目并请求秒杀。

期望：

- ReturnCallback 触发。
- `seckill:pending:data:{orderId}` 状态为 `ROUTE_FAILED`。
- 修复 routingKey 后，定时任务重投成功。

### 12.4 消费者异常重试

模拟方式：

- 临时在 `createVouchOrder()` 中抛异常。

期望：

- 消息从主队列进入重试队列。
- TTL 到期后回到主队列。
- 超过 3 次后进入失败队列。
- 失败队列消费者记录 `FAILED`，必要时恢复 Redis 预扣。

### 12.5 重复消费

模拟方式：

- 手动向订单交换机发送两条相同 `orderId/userId/voucherId` 的消息。

期望：

- 第一条创建订单。
- 第二条触发主键或 `uk_user_voucher` 唯一索引。
- 消费者捕获 `DuplicateKeyException` 后 ACK。
- 不会重复扣 MySQL 库存。

## 13. 最终功能完成度对照表

| 方案要求 | 当前项目 | 补本文后 |
|---|---|---|
| RabbitMQ 异步创建订单 | 已实现 | 保持 |
| Publisher Confirm | 已配置，只有日志 | ACK 清理，NACK 进入补偿 |
| Publisher Return | 已配置，只有日志 | Return 进入补偿 |
| Queue / Exchange 持久化 | 已实现 | 保持 |
| Consumer 手动 ACK | 已实现 | 成功后顺带清理 PENDING |
| 消费失败有限重试 | 已实现 | 保持 |
| DLQ / 失败队列 | 已实现队列，缺处理 | 新增失败队列消费者 |
| 重复消费幂等 | 已实现数据库兜底 | 保持 |
| Redis 预扣成功但 MQ 发送失败可补偿 | 未实现 | Lua PENDING + 定时重投 |
| Pending 最终收敛 | 未实现 | DB 对账清理 / FAILED 归档 |

## 14. 建议提交顺序

```text
feat: record pending voucher orders in redis lua
feat: persist rabbitmq publisher nack and return states
feat: republish pending voucher order messages
feat: clean pending order state after idempotent consume
feat: handle failed voucher order queue
docs: add rabbitmq reliability completion guide
```

## 15. 面试表达版本

可以这样概括：

> 项目原本已经用 RabbitMQ 替代 JVM 阻塞队列实现异步下单，并配置了 Publisher Confirm、Return、手动 ACK、TTL 重试队列、失败队列和数据库唯一索引。但 Confirm NACK 和 Return 之前只记录日志，无法解决 Redis 预扣成功但 MQ 没有可靠落队列的问题。升级后，我在 Redis Lua 中原子记录 PENDING 订单，生产者根据 Confirm / Return 更新投递状态，定时任务扫描超时 PENDING 并先查 MySQL 再重投，消费者成功或重复消费时清理 PENDING，失败队列消费者负责最终失败记录和必要的 Redis 预扣恢复。这样把不可感知的消息丢失变成了可扫描、可重试、可对账的状态。
