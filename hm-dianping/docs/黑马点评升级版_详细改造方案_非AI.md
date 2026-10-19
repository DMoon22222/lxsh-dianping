# 黑马点评升级版：详细改造方案（不含 AI 助手）

> 适用对象：已经完成或接近完成原版“黑马点评（hm-dianping）”项目，希望将其改造成更适合 Java 后端实习/校招简历的工程化升级版本。
>
> 本方案**不包含 AI 助手 / Spring AI / RAG**，重点放在真正与 Java 后端、高并发、缓存、消息队列、分布式一致性相关的升级。
>
> 推荐最终技术栈：**Spring Boot + MyBatis-Plus + MySQL + Redis + Lua + Redisson + RabbitMQ + Caffeine + Docker Compose + JMeter**。

---

## 目录

1. [改造目标与最终架构](#1-改造目标与最终架构)
2. [改造优先级与实施顺序](#2-改造优先级与实施顺序)
3. [第一阶段：RabbitMQ 可靠异步下单改造](#3-第一阶段rabbitmq-可靠异步下单改造)
4. [第二阶段：Caffeine + Redis 多级缓存](#4-第二阶段caffeine--redis-多级缓存)
5. [第三阶段：缓存一致性与失效广播](#5-第三阶段缓存一致性与失效广播)
6. [第四阶段：Redis + Lua + AOP 滑动窗口限流](#6-第四阶段redis--lua--aop-滑动窗口限流)
7. [第五阶段：订单取消、超时释放与库存回流](#7-第五阶段订单取消超时释放与库存回流)
8. [第六阶段：售罄订阅 / 到券提醒](#8-第六阶段售罄订阅--到券提醒)
9. [第七阶段：可靠性补偿与定时对账](#9-第七阶段可靠性补偿与定时对账)
10. [第八阶段：压测、监控与性能数据](#10-第八阶段压测监控与性能数据)
11. [Docker 环境建议](#11-docker-环境建议)
12. [推荐项目目录](#12-推荐项目目录)
13. [按文件划分的实际改造清单](#13-按文件划分的实际改造清单)
14. [推荐 Git 提交顺序](#14-推荐-git-提交顺序)
15. [最终验收清单](#15-最终验收清单)
16. [简历写法参考](#16-简历写法参考)
17. [面试时必须能解释的问题](#17-面试时必须能解释的问题)
18. [官方资料](#18-官方资料)

---

# 1. 改造目标与最终架构

## 1.1 不要把“教程已有功能”当升级点

原版黑马点评已经涉及很多 Redis 经典问题，例如：

- Redis 登录 Session；
- 缓存穿透；
- 缓存击穿；
- 缓存雪崩；
- Lua 秒杀资格判断；
- 一人一单；
- 分布式锁；
- 异步下单；
- GEO 附近商户；
- BitMap 签到；
- ZSet 点赞排行；
- 共同关注等。

因此，升级时重点不是把这些内容再写一次，而是围绕以下工程问题继续向前做：

1. **消息是否可靠？**
2. **消费者是否幂等？**
3. **Redis 预扣成功、MQ 发送失败怎么办？**
4. **本地缓存 + Redis 后，一致性怎么处理？**
5. **接口高并发时如何限流？**
6. **订单取消或超时后库存怎样安全回流？**
7. **售罄后能否订阅到券提醒？**
8. **系统异常后能否补偿、对账并恢复？**
9. **优化效果是否有真实压测数据证明？**

这些才是升级后面试官真正会追问的内容。

---

## 1.2 推荐最终架构

```text
                         ┌────────────────────┐
                         │       Client       │
                         └─────────┬──────────┘
                                   │
                                   ▼
                         ┌────────────────────┐
                         │   Spring Boot API  │
                         │  AOP Rate Limiter  │
                         └──────┬───────┬─────┘
                                │       │
                   查询类请求    │       │ 秒杀/订单命令
                                ▼       ▼
                       ┌────────────┐   ┌──────────────┐
                       │  Caffeine  │   │ Redis + Lua  │
                       └─────┬──────┘   │资格/库存预扣 │
                             │ miss      └──────┬───────┘
                             ▼                  │
                       ┌────────────┐            ▼
                       │   Redis    │      ┌─────────────┐
                       │ 分布式缓存 │      │  RabbitMQ   │
                       └─────┬──────┘      └──────┬──────┘
                             │ miss               │
                             ▼                    ▼
                       ┌────────────┐      ┌─────────────┐
                       │   MySQL    │◄─────│ OrderConsumer│
                       └─────┬──────┘      └─────────────┘
                             │
                             │ 更新后
                             ▼
                    ┌───────────────────┐
                    │ Cache Invalid Msg │
                    │ Inventory Event   │
                    └─────────┬─────────┘
                              ▼
                         RabbitMQ
```

### 秒杀主链路

```text
请求
 → 限流
 → Lua 原子判断资格
 → Redis 预扣库存
 → Redis 记录 PENDING 消息
 → RabbitMQ 发布订单消息
 → Consumer 消费
 → MySQL 事务创建订单
 → ACK
 → 清理 PENDING 状态
```

### 商户查询链路

```text
请求
 → Caffeine
 → Redis
 → MySQL
 → 回填 Redis
 → 回填 Caffeine
```

### 数据更新链路

```text
更新 MySQL
 → 事务提交
 → 删除 Redis
 → 删除本实例 Caffeine
 → RabbitMQ 广播缓存失效事件
 → 其他实例删除自己的 Caffeine
 → TTL 最终兜底
```

---

# 2. 改造优先级与实施顺序

不建议同时改所有模块。按下面顺序做，每完成一个阶段都能独立运行和提交 Git。

| 阶段 | 改造内容 | 优先级 | 简历价值 | 难度 |
|---|---|---:|---:|---:|
| 1 | RabbitMQ 可靠异步下单 | P0 | ★★★★★ | ★★★★ |
| 2 | Caffeine + Redis 多级缓存 | P0 | ★★★★★ | ★★★ |
| 3 | 缓存一致性 / 广播失效 | P0 | ★★★★★ | ★★★★ |
| 4 | Redis + Lua 滑动窗口限流 | P0 | ★★★★ | ★★★ |
| 5 | 订单取消 / 超时 / 库存回流 | P1 | ★★★★ | ★★★★ |
| 6 | 售罄订阅 / 到券提醒 | P1 | ★★★★ | ★★★ |
| 7 | 定时补偿 / 对账 | P1 | ★★★★★ | ★★★★ |
| 8 | JMeter 压测 | P0 | ★★★★★ | ★★★ |

> 如果时间有限，至少做到 **1 + 2 + 3 + 4 + 8**。这五项已经足够让项目与教程原版拉开明显差异。

---

# 3. 第一阶段：RabbitMQ 可靠异步下单改造

## 3.1 改造目的

原版使用 JVM 阻塞队列或 Redis Stream 时，学习价值很高，但作为“工程化项目”还缺少完整的消息可靠性设计。

本阶段目标是解决：

- 消息发送失败；
- Exchange 找不到匹配 Queue；
- Broker 收到消息后生产者不知道；
- Consumer 消费过程中宕机；
- 消费失败无限重试；
- 消息重复消费；
- Redis 扣库存成功但订单消息没有成功落到 MQ；
- MySQL 订单已成功，但 ACK 丢失导致重复投递。

---

## 3.2 引入依赖

`pom.xml`：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

如果项目已经有 RabbitMQ 相关依赖，不重复添加。

---

## 3.3 RabbitMQ 拓扑设计

推荐至少建立：

```text
voucher.order.exchange
    │
    ├── routingKey: voucher.order.create
    │       ↓
    │   voucher.order.queue
    │
    └── 消费失败最终进入
            ↓
       voucher.order.dlx
            ↓
       voucher.order.dlq
```

推荐常量：

```java
public final class MqConstants {

    public static final String ORDER_EXCHANGE = "voucher.order.exchange";
    public static final String ORDER_QUEUE = "voucher.order.queue";
    public static final String ORDER_ROUTING_KEY = "voucher.order.create";

    public static final String ORDER_DLX = "voucher.order.dlx";
    public static final String ORDER_DLQ = "voucher.order.dlq";
    public static final String ORDER_DLQ_ROUTING_KEY = "voucher.order.dead";
}
```

---

## 3.4 RabbitMQ 配置类

新增：

```text
config/RabbitMqConfig.java
```

示意：

```java
@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange voucherOrderExchange() {
        return ExchangeBuilder
                .directExchange(MqConstants.ORDER_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange voucherOrderDlx() {
        return ExchangeBuilder
                .directExchange(MqConstants.ORDER_DLX)
                .durable(true)
                .build();
    }

    @Bean
    public Queue voucherOrderQueue() {
        return QueueBuilder
                .durable(MqConstants.ORDER_QUEUE)
                .deadLetterExchange(MqConstants.ORDER_DLX)
                .deadLetterRoutingKey(MqConstants.ORDER_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue voucherOrderDlq() {
        return QueueBuilder
                .durable(MqConstants.ORDER_DLQ)
                .build();
    }

    @Bean
    public Binding orderBinding() {
        return BindingBuilder
                .bind(voucherOrderQueue())
                .to(voucherOrderExchange())
                .with(MqConstants.ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding orderDlqBinding() {
        return BindingBuilder
                .bind(voucherOrderDlq())
                .to(voucherOrderDlx())
                .with(MqConstants.ORDER_DLQ_ROUTING_KEY);
    }
}
```

### 为什么需要 DLQ？

不能让一条永远处理失败的“毒消息”一直回到主队列形成死循环。

推荐策略：

```text
普通异常
 → 有限次数重试
 → 仍失败
 → DLQ
 → 记录日志 / 后台补偿 / 人工检查
```

---

## 3.5 消息对象不要直接发送数据库 Entity

新增：

```text
message/VoucherOrderMessage.java
```

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderMessage implements Serializable {

    private String messageId;

    private Long orderId;

    private Long userId;

    private Long voucherId;

    private LocalDateTime createTime;
}
```

### 为什么单独设计 Message DTO？

数据库 Entity 是持久层模型，MQ Message 是跨组件协议。

以后数据库字段增加：

```text
payTime
useTime
refundTime
```

不意味着这些字段都需要传给 RabbitMQ。

这能减少消息体、降低耦合，也更符合实际项目设计。

---

## 3.6 数据库增加唯一约束作为最终幂等防线

如果当前项目没有，应检查 `tb_voucher_order` 是否存在：

```sql
ALTER TABLE tb_voucher_order
ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
```

### 为什么 Redis 判断过“一人一单”还需要数据库唯一索引？

因为 Redis 不是最终数据约束。

可能出现：

```text
消息重复消费
Redis 数据被误删
并发异常
补偿任务重复执行
应用代码 Bug
```

数据库唯一约束负责提供最后一道防线。

> 本方案默认业务规则：**用户取消订单后仍视为参与过该券，不允许再次秒杀同一券**。这样 `(user_id, voucher_id)` 唯一索引最简单、最可靠。
>
> 如果未来明确要求“取消后允许重新购买”，需要重新设计唯一约束与订单生命周期，不要直接删除 Redis 一人一单标记却保留该唯一索引，否则逻辑会冲突。

---

## 3.7 改造秒杀 Lua 脚本

原版常见逻辑：

```text
判断库存
判断是否下过单
扣库存
保存用户 ID
```

升级后增加一个关键步骤：

> **原子记录待发送订单（PENDING）。**

Redis Key 示例：

```text
seckill:stock:{voucherId}
seckill:order:{voucherId}
seckill:pending
seckill:pending:data:{orderId}
```

`seckill:pending` 推荐使用 ZSet：

```text
member = orderId
score  = 创建时间戳
```

同时保存订单基础数据：

```text
seckill:pending:data:{orderId}
```

内容可以是 Hash 或 JSON。

### 为什么要记录 PENDING？

考虑如下极端情况：

```text
Lua 扣库存成功
       ↓
Java 准备调用 rabbitTemplate
       ↓
服务器突然断电
```

如果没有 PENDING 记录：

```text
Redis 库存少了 1
但是 RabbitMQ 中没有消息
MySQL 也没有订单
```

系统无法知道这笔订单丢在哪里。

有 PENDING 后：

```text
定时任务扫描超时 PENDING
 → 判断是否已经存在订单
 → 不存在则重新发送 MQ
```

从而把故障变成“可恢复状态”。

---

## 3.8 Lua 伪代码

```lua
-- KEYS / ARGV 根据你的项目习惯调整

-- 1. 判断库存
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

-- 2. 判断是否已经购买
if redis.call('SISMEMBER', orderKey, userId) == 1 then
    return 2
end

-- 3. 扣减库存
redis.call('DECR', stockKey)

-- 4. 添加购买用户
redis.call('SADD', orderKey, userId)

-- 5. 加入待发送集合
redis.call('ZADD', pendingKey, timestamp, orderId)

-- 6. 保存待发送消息必要数据
redis.call('HSET', pendingDataKey,
    'orderId', orderId,
    'userId', userId,
    'voucherId', voucherId,
    'timestamp', timestamp)
redis.call('EXPIRE', pendingDataKey, 86400)

return 0
```

> 建议不要把一大段复杂 JSON 拼接逻辑全部塞进 Lua。Lua 只负责必须原子的判断与写入。

---

## 3.9 Java 秒杀接口改造

最终 `seckillVoucher()` 不再自己创建数据库订单。

逻辑应变成：

```java
public Result seckillVoucher(Long voucherId) {

    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");

    // 1. 执行 Lua
    Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(),
            userId.toString(),
            String.valueOf(orderId),
            String.valueOf(System.currentTimeMillis())
    );

    if (result == null) {
        return Result.fail("系统繁忙");
    }

    if (result == 1) {
        return Result.fail("库存不足");
    }

    if (result == 2) {
        return Result.fail("不能重复下单");
    }

    // 2. 构造消息
    VoucherOrderMessage message = ...;

    // 3. 发送 RabbitMQ
    voucherOrderProducer.send(message);

    // 4. 立即返回订单 ID，不等待数据库落库
    return Result.ok(orderId);
}
```

---

## 3.10 开启 Publisher Confirm + Return

这里需要区分两个概念：

### Confirm

回答：

> Broker 是否接收到了 Publisher 发送的消息？

### Return

回答：

> Exchange 收到消息之后，是否找到匹配的 Queue？

二者解决的问题不同，建议都处理。

配置项的准确名称会随 Spring Boot / Spring AMQP 小版本有所差异，因此请按当前项目版本的官方配置属性填写；核心目标是：

```text
publisher confirm = correlated
publisher returns = true
mandatory = true
```

生产者：

```text
mq/VoucherOrderProducer.java
```

发送时为消息绑定 `CorrelationData`：

```java
CorrelationData correlationData = new CorrelationData(message.getMessageId());

rabbitTemplate.convertAndSend(
        MqConstants.ORDER_EXCHANGE,
        MqConstants.ORDER_ROUTING_KEY,
        message,
        correlationData
);
```

Confirm ACK：

```text
Broker 成功接收
 → 将 pending 状态改为 SENT / 或从“待发送集合”移除
```

Confirm NACK：

```text
记录失败
 → 保留 pending
 → 交给补偿任务重新发送
```

Return：

```text
消息到 Exchange 但无法路由
 → 记录高优先级错误
 → 保留 pending
 → 补偿重发
```

### 为什么不要在 NACK 回调里直接疯狂循环重发？

因为 Broker 故障时会形成：

```text
失败
 → 立即重发
 → 失败
 → 立即重发
```

可能造成故障放大。

更好的方式：

```text
回调只记录失败状态
 → 定时补偿任务按退避策略重发
```

---

## 3.11 Consumer 使用手动 ACK

新增：

```text
listener/VoucherOrderConsumer.java
```

核心思想：

```text
收到消息
 → 数据库事务处理成功
 → basicAck

异常
 → 有限重试
 → 最终 basicReject/basicNack(requeue=false)
 → DLQ
```

不要：

```text
一收到消息立即 ACK
然后再操作数据库
```

否则：

```text
ACK 成功
 → Java 进程崩溃
 → MySQL 没写入
 → RabbitMQ 不再投递
```

订单永久丢失。

---

## 3.12 Consumer 数据库事务

建议把真正的业务事务放进独立 Service，避免 `@Transactional` 自调用失效问题。

```text
VoucherOrderConsumer
        ↓
VoucherOrderTransactionalService
        ↓
MySQL Transaction
```

核心数据库事务：

```java
@Transactional
public void createVoucherOrder(VoucherOrderMessage message) {

    // 1. 数据库再次检查/唯一索引兜底

    // 2. DB 库存做条件扣减，作为第二道防线
    boolean success = update()
            .setSql("stock = stock - 1")
            .eq("voucher_id", message.getVoucherId())
            .gt("stock", 0)
            .update();

    if (!success) {
        throw new StockNotEnoughException();
    }

    // 3. 插入订单
    save(order);
}
```

### 为什么 Redis 已经扣库存，MySQL 还要 `stock > 0`？

Redis 是高并发入口库存，MySQL 是最终持久库存。

DB 条件更新用于避免异常情况下库存变成负数。

---

## 3.13 消费幂等设计

推荐将**数据库唯一索引作为权威幂等保证**。

可额外使用 Redis：

```text
mq:processed:{messageId}
```

但不要犯这个错误：

```text
SETNX processed = 1
 → 数据库操作
 → 数据库失败
```

如果数据库失败，而 processed 已经永久存在，下一次重试会被误判为“已处理”。

推荐：

```text
先查询 processed（仅作快速路径）
 → 执行数据库事务
 → 成功提交
 → 再写 processed
```

即使 Redis processed 写失败：

```text
消息再次消费
 → 数据库唯一约束阻止重复订单
```

因此 Redis 去重是优化，数据库约束才是底线。

---

## 3.14 异常分类

不要所有异常都无限重试。

### 可重试异常

例如：

```text
数据库连接瞬时失败
Redis 短暂超时
网络抖动
锁等待超时
```

### 不应无限重试的异常

例如：

```text
消息字段缺失
JSON 反序列化失败
voucherId 非法
业务数据不存在
程序逻辑 Bug
```

最终进入 DLQ。

---

## 3.15 第一阶段验收测试

至少手动测试：

### Case 1：正常秒杀

```text
Redis stock -1
RabbitMQ 收到消息
MySQL 生成订单
Consumer ACK
pending 消失
```

### Case 2：同一用户连续请求

最终：

```text
只有一笔订单
```

### Case 3：Consumer 在事务前抛异常

应看到：

```text
消息没有被错误 ACK
```

### Case 4：故意将 routing key 配错

应触发：

```text
ReturnCallback
pending 不删除
```

### Case 5：消息重复投递两次

数据库最终只能有一条订单。

### Case 6：Consumer 一直失败

最终应进入 DLQ，而不是主队列无限循环。

---

# 4. 第二阶段：Caffeine + Redis 多级缓存

## 4.1 改造目标

原版：

```text
Application
    ↓
Redis
    ↓
MySQL
```

升级后：

```text
Application
    ↓
Caffeine Local Cache
    ↓ miss
Redis
    ↓ miss
MySQL
```

Caffeine 位于应用 JVM 内，不需要网络请求，因此非常适合缓存：

- 热门商户详情；
- 商户类型；
- 热门榜单；
- 更新频率较低的数据。

不推荐缓存：

- 秒杀实时库存；
- 用户实时订单状态；
- 强实时计数器。

---

## 4.2 引入 Caffeine

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

---

## 4.3 Caffeine 必须设置最大容量

错误：

```java
Caffeine.newBuilder().build();
```

如果长期不断写 Key，可能导致 JVM 内存压力。

建议：

```java
@Bean
public Cache<Long, Shop> shopLocalCache() {
    return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .recordStats()
            .build();
}
```

这里的 `10_000`、`60s` 只是**初始测试值**，不要当成固定最佳参数。

应该根据：

```text
商户数量
单条对象大小
JVM 堆大小
更新频率
命中率
可接受陈旧时间
```

来调整。

---

## 4.4 商户详情查询流程

推荐建立：

```text
cache/ShopCacheService.java
```

逻辑：

```text
1. 查询 Caffeine
   ├─ 命中 → 返回
   └─ miss

2. 查询 Redis
   ├─ 命中 → 写入 Caffeine → 返回
   └─ miss

3. 查询 MySQL
   ├─ 不存在 → Redis 写短 TTL 空值
   └─ 存在 → Redis 写缓存 + Caffeine 写缓存
```

伪代码：

```java
public Shop queryShopById(Long id) {

    // L1
    Shop local = shopCache.getIfPresent(id);
    if (local != null) {
        return local;
    }

    String key = CACHE_SHOP_KEY + id;

    // L2
    String json = stringRedisTemplate.opsForValue().get(key);

    if (StrUtil.isNotBlank(json)) {
        Shop shop = JSONUtil.toBean(json, Shop.class);
        shopCache.put(id, shop);
        return shop;
    }

    // 空值标记
    if (json != null) {
        return null;
    }

    // DB
    Shop shop = getById(id);

    if (shop == null) {
        stringRedisTemplate.opsForValue().set(
                key,
                "",
                CACHE_NULL_TTL,
                TimeUnit.MINUTES
        );
        return null;
    }

    setShopCacheWithRandomTtl(shop);
    shopCache.put(id, shop);

    return shop;
}
```

---

## 4.5 Redis TTL 增加随机抖动

假设大量商户缓存同时写入：

```text
TTL 都是 30 分钟
```

30 分钟后可能同时过期。

建议：

```text
基础 TTL + 随机值
```

例如：

```java
long ttl = 30 + ThreadLocalRandom.current().nextLong(0, 10);
```

最终：

```text
30~40 分钟随机过期
```

降低大量 Key 同一时刻失效造成的缓存雪崩概率。

---

## 4.6 热点缓存与普通缓存分开

建议不要所有商户都使用同一种缓存策略。

### 普通商户

```text
Caffeine 60s
Redis TTL 30~40min
```

### 极热点商户

可以保留原版“逻辑过期 + 异步重建”方案：

```text
Redis 数据本身不过期 / 长 TTL
JSON 中携带 logicalExpireTime
```

查询发现逻辑过期：

```text
先返回旧数据
 → 尝试获取重建锁
 → 后台线程更新缓存
```

这样高峰期不会让热点请求直接打到数据库。

---

## 4.7 防止缓存击穿的两层设计

单实例：

```text
Caffeine Cache.get(key, loader)
```

可以降低同一 JVM 内重复回源。

多实例：

还需要 Redis 分布式互斥：

```text
lock:shop:{id}
```

因为：

```text
实例 A Caffeine miss
实例 B Caffeine miss
实例 C Caffeine miss
```

如果 Redis 也刚好过期，三个实例仍可能一起查数据库。

---

# 5. 第三阶段：缓存一致性与失效广播

这是多级缓存最重要的一部分。

只写：

> “我使用了 Caffeine + Redis 二级缓存”

面试官下一句基本就是：

> “那本地缓存和 Redis 怎么保证一致？”

---

## 5.1 最容易犯的错误

假设部署两个实例：

```text
App-A
App-B
```

用户在 App-A 修改商户：

```text
更新 DB
删除 Redis
删除 App-A Caffeine
```

但：

```text
App-B 的 Caffeine 仍然保留旧值
```

之后访问 App-B：

```text
直接命中旧数据
连 Redis 都不会查
```

所以仅删除本机 Caffeine 是错误的多实例设计。

---

## 5.2 推荐方案：RabbitMQ Fanout 广播失效

建立：

```text
cache.invalidate.exchange (Fanout)
```

每个实例创建自己的监听 Queue：

```text
cache.invalidate.{instanceId}
```

例如：

```text
cache.invalidate.app-1
cache.invalidate.app-2
cache.invalidate.app-3
```

全部绑定同一个 Fanout Exchange。

这样发送一次：

```json
{
  "cacheName": "shop",
  "key": "1001"
}
```

三个实例都能收到。

### 非常重要

不要让所有实例监听同一个普通 Queue：

```text
cache.invalidate.queue
```

因为 RabbitMQ 一个队列的多个消费者默认属于**竞争消费**：

```text
同一条消息通常只会交给其中一个消费者
```

这不满足“每个应用实例都清本地缓存”的需求。

缓存失效广播应当采用：

```text
Fanout Exchange
  + 每实例独立 Queue
```

由于实例重启后本机 Caffeine 本身就会丢失，因此这些实例级缓存失效 Queue 通常可以设计成非持久 / auto-delete；是否采用该方式根据你的部署模式决定。

---

## 5.3 更新商户的正确顺序

推荐：

```text
1. 开启数据库事务
2. UPDATE MySQL
3. COMMIT
4. 删除 Redis
5. 删除本机 Caffeine
6. 发布缓存失效广播
7. 其他实例删除本地 Caffeine
8. Redis/Caffeine TTL 最终兜底
```

重点：

> **不要在数据库事务尚未提交时就通知其他实例更新缓存。**

否则事务最后回滚，其他实例却已经开始重新加载错误状态。

可以使用：

```text
@TransactionalEventListener(phase = AFTER_COMMIT)
```

或事务同步回调，在提交之后执行失效动作。

---

## 5.4 为什么是删除缓存而不是直接更新缓存？

更新 DB 后同时更新：

```text
Redis
Caffeine A
Caffeine B
Caffeine C
```

需要保证多个地方都更新成功，写路径会越来越复杂。

Cache Aside 更常见的思路是：

```text
DB 更新
 → 缓存失效
 → 下一次查询重新加载
```

把一致性目标从“同步写多个副本”变成“让旧副本尽快失效”。

---

## 5.5 删除 Redis 失败怎么办？

推荐：

```text
同步删除失败
 → 发布 cache.invalidate 消息
 → Consumer 再次删除 Redis / Caffeine
 → TTL 最终兜底
```

也可以记录：

```text
cache:invalidate:failed
```

由定时补偿任务重新处理。

---

## 5.6 是否要做“延迟双删”？

可以作为**可选增强**，不要把它描述成“绝对保证一致性”。

典型竞态：

```text
线程 A 查询 DB 旧值
线程 B 更新 DB 并删除缓存
线程 A 把刚才读到的旧值重新写回缓存
```

可以在更新后增加一次延迟失效：

```text
立即删除
 → 延迟几百毫秒再次删除
```

但延迟时间并没有一个适合所有系统的固定答案。

因此简历建议表述：

> 采用事务提交后缓存失效、MQ 广播、失败重试与 TTL 兜底实现最终一致性；针对并发回填旧值场景增加二次失效策略以缩短不一致窗口。

不要写：

> “保证 Redis、Caffeine、MySQL 强一致。”

这是不准确的。

---

# 6. 第四阶段：Redis + Lua + AOP 滑动窗口限流

## 6.1 改造目标

需要限制：

- 短信验证码接口；
- 登录接口；
- 秒杀接口；
- 高频查询接口；
- 恶意用户 / IP。

单纯用：

```text
AtomicInteger
ConcurrentHashMap
```

只能限制单个 JVM 实例。

项目升级后推荐 Redis 分布式限流。

---

## 6.2 为什么使用滑动窗口？

固定窗口存在边界突刺：

限制：

```text
1 分钟 100 次
```

用户可能：

```text
12:00:59 请求 100 次
12:01:00 请求 100 次
```

两秒内实际放过 200 次。

滑动窗口会根据当前时刻向前计算完整时间区间，边界更平滑。

---

## 6.3 Redis 数据结构

使用 ZSet：

```text
Key:
rate:limit:{维度}

score:
请求时间戳

member:
requestId
```

每次请求：

```text
1. 删除 window 之前的记录
2. ZCARD 统计当前窗口请求数
3. 达到 limit → 拒绝
4. 未达到 → ZADD 当前请求
5. 设置 Key 过期时间
```

必须放进 Lua 原子执行。

---

## 6.4 Lua 限流脚本

建议使用 Redis 自己的 `TIME` 获取时间，而不是依赖每台应用服务器本地时钟完全同步。

示意：

```lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local requestId = ARGV[3]

local redisTime = redis.call('TIME')
local nowMs = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
local minScore = nowMs - windowMs

redis.call('ZREMRANGEBYSCORE', key, 0, minScore)

local count = redis.call('ZCARD', key)

if count >= limit then
    return 0
end

redis.call('ZADD', key, nowMs, tostring(nowMs) .. '-' .. requestId)
redis.call('PEXPIRE', key, windowMs + 1000)

return 1
```

---

## 6.5 自定义注解

新增：

```text
annotation/RateLimit.java
```

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    int limit();

    long windowMs();

    RateLimitType type() default RateLimitType.USER;

    String key() default "";
}
```

类型：

```java
public enum RateLimitType {
    USER,
    IP,
    GLOBAL,
    USER_AND_RESOURCE
}
```

---

## 6.6 AOP 统一处理

新增：

```text
aspect/RateLimitAspect.java
```

处理步骤：

```text
读取 @RateLimit
 → 获得当前 userId / IP / voucherId
 → 生成 Redis Key
 → 执行 Lua
 → 0：抛 TooManyRequestsException
 → 1：放行
```

---

## 6.7 推荐限流 Key

### 短信验证码

```text
rl:sms:phone:{phone}
```

可以同时增加：

```text
rl:sms:ip:{ip}
```

### 用户秒杀

```text
rl:seckill:user:{voucherId}:{userId}
```

### 秒杀总流量

```text
rl:seckill:global:{voucherId}
```

### 登录

```text
rl:login:ip:{ip}
```

---

## 6.8 参数不要硬写进简历

开发时可以先使用测试值，例如：

```text
验证码：1 分钟 1 次
用户秒杀：1 秒 3 次
券总请求：1 秒 1000 次
```

但这些是测试起点，不是“业界固定参数”。

最终应该根据 JMeter 压测结果调整。

---

## 6.9 限流失败返回统一错误码

例如：

```json
{
  "code": 429,
  "message": "请求过于频繁，请稍后重试"
}
```

并记录：

```text
limitType
userId
ip
api
resourceId
```

用于后续分析。

---

# 7. 第五阶段：订单取消、超时释放与库存回流

这部分是非常好的“业务魔改”，能让项目不只是中间件堆叠。

---

## 7.1 增加订单状态机

建议不要只用：

```text
0 / 1 / 2 / 3
```

代码里到处出现魔法数字。

定义：

```java
public enum VoucherOrderStatus {
    CREATED,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    USED
}
```

根据你的业务实际删减。

---

## 7.2 数据表字段

如果原表没有，可以按需增加：

```sql
ALTER TABLE tb_voucher_order
    ADD COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态',
    ADD COLUMN expire_time DATETIME NULL COMMENT '订单超时时间',
    ADD COLUMN cancel_time DATETIME NULL COMMENT '取消时间',
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
```

> 执行前先查看你当前项目表结构；如果已经存在同义字段，不要重复添加。

---

## 7.3 状态更新必须使用条件更新

不要：

```sql
UPDATE tb_voucher_order
SET status = CANCELLED
WHERE id = ?;
```

推荐：

```sql
UPDATE tb_voucher_order
SET status = CANCELLED,
    cancel_time = NOW()
WHERE id = ?
  AND user_id = ?
  AND status = CREATED;
```

通过受影响行数判断是否成功。

为什么？

可能同时发生：

```text
用户取消
超时任务取消
用户确认
```

条件状态更新可以保证只有符合前置状态的操作成功。

---

## 7.4 不推荐“取消事务里直接同时改 MySQL + Redis + RabbitMQ”

例如：

```text
MySQL status = CANCELLED
Redis stock +1
RabbitMQ send
```

这是三个不同系统，没有一个共同事务。

任何一步失败都会产生不一致。

---

## 7.5 推荐：DB Outbox 处理库存回流事件

新增表：

```sql
CREATE TABLE tb_outbox_event (
    id BIGINT PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_retry (status, next_retry_time)
);
```

取消订单的**同一个 MySQL 事务**中：

```text
1. CREATED → CANCELLED
2. DB stock + 1
3. INSERT outbox_event(INVENTORY_RETURN)
4. COMMIT
```

这样至少能保证：

```text
订单取消
库存 DB 回滚
回流事件记录
```

三者在同一数据库事务中一起成功或一起失败。

之后独立 Publisher：

```text
扫描 NEW outbox
 → 发布 RabbitMQ
 → Confirm ACK
 → status = SENT
```

失败：

```text
retry_count +1
next_retry_time 延后
```

---

## 7.6 库存回流 Consumer

收到：

```text
INVENTORY_RETURN
```

执行：

```text
Redis stock + 1
 → 触发售罄订阅通知逻辑
```

注意库存回流消息也可能重复投递，因此需要事件幂等。

推荐：

```text
event:processed:{eventId}
```

但仍应保证 Redis 操作本身具备可重复执行控制。

例如使用 Lua：

```text
if eventId 未处理
    INCR stock
    写 processed
end
```

原子完成。

---

## 7.7 取消后是否允许再次抢购？

这是一定要提前确定的业务规则。

### 推荐简化规则

> 同一用户参加过同一张秒杀券后，即使取消，也不允许再次参加。

优点：

```text
数据库唯一索引不需要改
Redis orderSet 不需要删除 userId
逻辑简单
更容易保证一致性
```

如果未来改成“取消后可以重新购买”，必须同步修改：

- 数据库唯一约束；
- Redis 一人一单标记；
- 订单状态判断；
- Consumer 幂等逻辑。

不要只做：

```text
SREM userId
```

否则 Redis 允许第二次购买，MySQL 唯一索引又会拒绝，系统会自相矛盾。

---

## 7.8 订单超时释放

例如：

```text
订单创建后 15 分钟未确认 → EXPIRED
```

可以使用 RabbitMQ TTL + DLX，无需强依赖额外的 delayed-message 插件。

拓扑：

```text
order.expire.delay.queue
    TTL = 15min
    dead-letter → order.expire.exchange
                       ↓
                 order.expire.queue
```

创建订单成功后发送：

```json
{
  "orderId": 123,
  "event": "ORDER_EXPIRE_CHECK"
}
```

15 分钟后 Consumer 收到。

不要直接无条件取消，而是：

```sql
UPDATE tb_voucher_order
SET status = EXPIRED
WHERE id = ?
  AND status = CREATED;
```

只有受影响行数为 1：

```text
DB stock +1
insert INVENTORY_RETURN outbox
```

如果用户已经确认：

```text
UPDATE 影响 0 行
 → 什么都不做
```

这样即使延迟消息晚到，也不会错误取消已确认订单。

---

# 8. 第六阶段：售罄订阅 / 到券提醒

建议先做一个**正确但不过度复杂**的版本：

> 售罄后用户可以订阅；库存回流后按订阅顺序通知用户“已有库存”，但不自动替用户创建订单。

这样比“自动候补下单”更容易保证业务正确性。

---

## 8.1 Redis 订阅队列

使用 ZSet：

```text
voucher:subscribe:{voucherId}
```

```text
score  = subscribeTimestamp
member = userId
```

订阅：

```text
ZADD key NX timestamp userId
```

`NX` 防止同一用户重复加入。

---

## 8.2 API

增加：

```text
POST /voucher/{voucherId}/subscribe
DELETE /voucher/{voucherId}/subscribe
GET /voucher/{voucherId}/subscribe/status
```

订阅前判断：

```text
券是否存在
是否在有效期
当前是否售罄
用户是否已经购买
用户是否已经订阅
```

---

## 8.3 库存回流触发通知

`INVENTORY_RETURN` Consumer 完成 `Redis stock +1` 后：

```text
检查 subscribe ZSet
 → ZPOPMIN 取最早订阅用户
 → 发布 VOUCHER_AVAILABLE 消息
```

通知形式在项目里可以先做：

- 站内消息表；
- Mock 短信；
- Mock 邮件；
- WebSocket（可选）。

如果时间有限，推荐做**站内通知表**，最稳定也最好演示。

---

## 8.4 站内通知表

```sql
CREATE TABLE tb_user_notification (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(512) NOT NULL,
    biz_id BIGINT NULL,
    read_status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_read (user_id, read_status, create_time)
);
```

用户可以查询：

```text
GET /notifications
POST /notifications/{id}/read
```

---

## 8.5 P1 可选增强：真正的候补资格

如果之后还有时间，再升级为：

```text
库存回流
 → 给最早候补用户锁定 5 分钟购买资格
 → 5 分钟没使用
 → 资格释放
 → 通知下一位
```

但这需要处理：

- 预留库存；
- 普通秒杀与候补库存竞争；
- 资格过期；
- 延迟消息；
- 资格消费幂等；
- 用户取消；
- 多个库存同时回流。

复杂度明显提高。

因此第一版简历写：

> “实现售罄订阅和库存回流通知。”

已经足够，不必为了“候补”两个字把系统改得过重。

---

# 9. 第七阶段：可靠性补偿与定时对账

这是把“用了 MQ”升级成“理解 MQ 可靠性”的关键。

---

## 9.1 秒杀 PENDING 补偿任务

新增：

```text
task/PendingOrderRepublishTask.java
```

每隔一段时间扫描：

```text
seckill:pending
```

只处理：

```text
创建超过阈值仍未 Confirm 成功的订单
```

流程：

```text
取 pending orderId
 → 查 pending data
 → 查询 MySQL 是否已经有订单

如果 DB 已有订单：
    清理 pending

如果 DB 无订单：
    重新发布 RabbitMQ
```

---

## 9.2 为什么先查 MySQL 再重发？

可能出现：

```text
消息已成功消费
MySQL 已插入
但应用在清理 pending 前宕机
```

如果任务直接重发，就会产生重复消息。

虽然 Consumer 有幂等，但能避免无意义重发更好。

---

## 9.3 重试次数与退避

Pending Data 增加：

```text
retryCount
lastRetryTime
```

例如：

```text
1 次：5 秒
2 次：30 秒
3 次：2 分钟
4 次：10 分钟
```

不要固定 10ms 疯狂重试。

超过最大次数：

```text
标记 FAILED
记录 ERROR 日志
进入人工检查列表
```

---

## 9.4 Outbox 补偿任务

扫描：

```sql
status = 'NEW'
AND next_retry_time <= NOW()
```

发送 MQ 成功：

```text
SENT
```

失败：

```text
retry_count + 1
更新 next_retry_time
```

为了防止多个实例同时扫描同一条，可以：

- 使用数据库状态抢占；
- 使用 `SELECT ... FOR UPDATE SKIP LOCKED`（需根据数据库版本/事务设计确认）；
- 或使用 ShedLock / Redis 分布式任务锁；
- 项目第一版也可以只启动一个补偿调度实例。

不要在没有实现的情况下在简历写“分布式调度”。

---

## 9.5 定时库存对账

可以做一个运维接口或定时任务检查：

```text
Redis stock
MySQL stock
未完成订单数量
pending 数量
DLQ 数量
```

不要简单要求：

```text
Redis stock == MySQL stock
```

因为在异步订单链路中会存在短时间正常差异。

更合理的是根据系统状态计算预期库存，并设置“超过时间阈值仍不一致”才报警。

项目第一版可以简化为：

```text
每分钟记录一次差异
差异持续超过 N 个周期 → WARN/ERROR
```

---

# 10. 第八阶段：压测、监控与性能数据

## 10.1 简历绝对不要编 QPS

下面这种数字只有你真实测出来才能写：

```text
QPS 从 12,000 提升到 26,000
P95 从 80ms 降到 35ms
```

面试官会问：

- 什么机器？
- 几核 CPU？
- 多大内存？
- Windows / Linux / Docker？
- 并发线程多少？
- 测哪个接口？
- 数据库数据量？
- Redis 是否本机？
- 是否开启日志？
- JVM 参数？
- 是否预热？
- P95 / P99？
- 错误率？

所以一定保留压测报告。

---

## 10.2 推荐 JMeter 测试场景

### 场景 A：商户详情

对比：

```text
MySQL only
Redis + MySQL
Caffeine + Redis + MySQL
```

记录：

```text
Throughput
Average
Median
P90
P95
P99
Error Rate
```

---

### 场景 B：热点 Key

例如：

```text
1000 用户持续请求同一个 shopId
```

观察：

- DB QPS；
- Redis QPS；
- Caffeine 命中；
- JVM CPU；
- 响应时间。

---

### 场景 C：秒杀

初始化：

```text
库存：1000
并发请求：例如 5000 / 10000
```

最终验证：

```text
成功订单 <= 1000
数据库库存 >= 0
Redis 库存 >= 0
同一用户最多 1 单
无重复订单
pending 最终归零或进入明确失败状态
```

这比单纯 QPS 更有价值。

---

### 场景 D：限流

配置：

```text
单用户 1 秒 3 次
```

同一用户瞬间发送：

```text
100 请求
```

验证：

```text
放行数量符合配置
其余返回 429
Redis 中窗口数据能够自动清理
```

---

## 10.3 Caffeine 统计

开启：

```java
.recordStats()
```

可以输出：

```text
hitRate
missRate
evictionCount
loadSuccessCount
loadFailureCount
```

简历最好写真实数据，例如：

> 在热点商户压测场景下，本地缓存命中率达到 XX%，Redis 访问量下降 XX%。

必须来自你的压测日志。

---

## 10.4 建议增加 Actuator

可选：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

重点看：

```text
JVM memory
threads
HTTP request latency
DB pool
cache metrics
```

第一版不必为了简历强上 Prometheus + Grafana。

如果你确实配置完成，再作为额外亮点。

---

# 11. Docker 环境建议

最终开发环境至少：

```text
MySQL
Redis
RabbitMQ + Management UI
Spring Boot Application
```

Caffeine 在 JVM 内，无独立容器。

推荐：

```text
docker-compose.yml
```

统一启动基础设施。

### RabbitMQ 端口

通常需要：

```text
5672   AMQP
15672  Management UI
```

### Redis

```text
6379
```

### MySQL

如果本机已经有 MySQL 占用 `3306`，不要再让 Docker 直接绑定同一个宿主机端口。

可以：

```text
3307:3306
```

Spring Boot：

```text
localhost:3307
```

如果 Spring Boot 也运行在同一个 Compose 网络内部，则直接通过 service name 访问 MySQL 容器的 `3306`，不必经过宿主机映射端口。

### 镜像版本

不要长期使用完全不固定的 `latest`。

在你跑通项目后，将 MySQL、Redis、RabbitMQ 镜像固定到**你实际测试通过的版本**，并在 README 记录。

---

# 12. 推荐项目目录

```text
src/main/java/com/hmdp
│
├── annotation
│   └── RateLimit.java
│
├── aspect
│   └── RateLimitAspect.java
│
├── cache
│   ├── ShopCacheService.java
│   ├── LocalCacheConfig.java
│   └── CacheKeys.java
│
├── config
│   ├── RabbitMqConfig.java
│   ├── RedisConfig.java
│   └── ThreadPoolConfig.java
│
├── controller
│   ├── VoucherOrderController.java
│   ├── VoucherSubscriptionController.java
│   └── NotificationController.java
│
├── dto
│   └── ...
│
├── entity
│   ├── VoucherOrder.java
│   ├── OutboxEvent.java
│   └── UserNotification.java
│
├── enums
│   ├── VoucherOrderStatus.java
│   ├── OutboxStatus.java
│   └── RateLimitType.java
│
├── listener
│   ├── VoucherOrderConsumer.java
│   ├── VoucherOrderDeadLetterConsumer.java
│   ├── InventoryReturnConsumer.java
│   ├── CacheInvalidateConsumer.java
│   └── VoucherAvailableConsumer.java
│
├── message
│   ├── VoucherOrderMessage.java
│   ├── InventoryReturnMessage.java
│   ├── CacheInvalidateMessage.java
│   └── VoucherAvailableMessage.java
│
├── mq
│   ├── VoucherOrderProducer.java
│   ├── CacheEventProducer.java
│   └── OutboxPublisher.java
│
├── service
│   ├── IVoucherOrderService.java
│   ├── VoucherOrderTransactionalService.java
│   ├── VoucherSubscriptionService.java
│   ├── CacheInvalidationService.java
│   └── OutboxService.java
│
├── task
│   ├── PendingOrderRepublishTask.java
│   ├── OutboxRetryTask.java
│   └── ReconciliationTask.java
│
└── utils
    └── ...

src/main/resources
│
├── lua
│   ├── seckill.lua
│   ├── sliding_window.lua
│   └── inventory_return.lua
│
├── application.yml
├── application-dev.yml
└── application-prod.yml
```

---

# 13. 按文件划分的实际改造清单

这一部分可以直接当作开发 TODO 使用。

---

## 13.1 `pom.xml`

新增：

```text
spring-boot-starter-amqp
caffeine
spring-boot-starter-actuator（可选）
```

检查：

```text
Redis
MyBatis-Plus
Redisson（如果项目已有）
```

---

## 13.2 `application.yml`

增加：

```text
RabbitMQ host
port
username
password
publisher confirm
publisher returns
consumer ack mode
```

不要把正式密码提交到 GitHub。

推荐：

```yaml
password: ${RABBITMQ_PASSWORD}
```

---

## 13.3 原 `VoucherOrderServiceImpl`

需要删除/替换：

```text
JVM BlockingQueue
内部异步线程循环
Redis Stream 消费循环（如果你决定完全替换）
```

保留：

```text
Lua 执行入口
ID 生成
秒杀资格返回码
```

新增：

```text
VoucherOrderProducer.send()
```

---

## 13.4 `seckill.lua`

在原有：

```text
库存判断
一人一单
DECR
SADD
```

基础上增加：

```text
ZADD pending
保存 pending data
```

---

## 13.5 新增 `VoucherOrderProducer`

负责：

```text
发送订单消息
CorrelationData
Confirm 处理
Return 处理
日志
```

不要让 Controller 直接堆一大堆 RabbitMQ 代码。

---

## 13.6 新增 `VoucherOrderConsumer`

负责：

```text
接收消息
调用 TransactionalService
事务成功后 ACK
异常分类
失败进入 DLQ
```

---

## 13.7 新增 `PendingOrderRepublishTask`

负责：

```text
扫描 PENDING
查 DB
重发
失败次数统计
最终 FAILED
```

---

## 13.8 原 `ShopServiceImpl`

原有：

```text
Redis 查询
缓存穿透
逻辑过期
```

升级：

```text
先查 Caffeine
再查 Redis
再查 MySQL
```

推荐把复杂缓存逻辑抽到：

```text
ShopCacheService
```

不要让 `ShopServiceImpl` 变成 500 行。

---

## 13.9 更新商户接口

修改：

```text
updateShop()
```

增加：

```text
事务提交后
 → 删除 Redis
 → invalidate Caffeine
 → 发送 cache.invalidate 广播
```

---

## 13.10 新增限流模块

```text
annotation/RateLimit.java
aspect/RateLimitAspect.java
enums/RateLimitType.java
resources/lua/sliding_window.lua
```

先应用到：

```text
sendCode()
seckillVoucher()
```

之后再逐步扩展。

---

## 13.11 订单状态升级

修改：

```text
VoucherOrder entity
tb_voucher_order
VoucherOrderController
```

增加：

```text
cancelOrder()
confirmOrder()（如果业务需要）
expireOrder()
```

所有状态变化使用条件更新。

---

## 13.12 Outbox

新增：

```text
entity/OutboxEvent.java
mapper/OutboxEventMapper.java
service/OutboxService.java
mq/OutboxPublisher.java
task/OutboxRetryTask.java
```

先只服务一个场景：

```text
订单取消 → 库存回流
```

不要一开始把所有 MQ 都改成 Outbox。

---

## 13.13 售罄订阅

新增：

```text
VoucherSubscriptionController
VoucherSubscriptionService
UserNotification
NotificationController
```

Redis：

```text
voucher:subscribe:{voucherId}
```

---

# 14. 推荐 Git 提交顺序

建议不要等全部完成一次性 commit。

示例：

```text
feat: add rabbitmq infrastructure for voucher orders

feat: replace async voucher order queue with rabbitmq

feat: add publisher confirm and return handling

feat: add idempotent voucher order consumer and dead letter queue

feat: add pending-order retry and reconciliation

feat: add caffeine local cache for shop queries

feat: broadcast local cache invalidation through rabbitmq

feat: add redis lua sliding-window rate limiter

feat: add voucher order lifecycle and cancellation

feat: add transactional outbox for inventory return events

feat: add voucher sold-out subscription notification

test: add jmeter scenarios for cache and seckill endpoints

docs: add architecture and benchmark report
```

这样 GitHub 提交历史可以体现你的改造过程，而不是“突然出现一份完整代码”。

---

# 15. 最终验收清单

## RabbitMQ

- [ ] 秒杀成功后通过 RabbitMQ 异步创建订单
- [ ] Publisher Confirm 已开启
- [ ] Return 已处理
- [ ] Queue / Exchange 持久配置符合预期
- [ ] Consumer 数据库成功后再 ACK
- [ ] 消费失败不会无限 requeue
- [ ] DLQ 可以看到最终失败消息
- [ ] 重复消息不会创建重复订单
- [ ] Redis Lua 成功但 MQ 发送失败可以补偿
- [ ] Pending 最终能收敛

## 缓存

- [ ] 商户详情先查 Caffeine
- [ ] Caffeine miss 再查 Redis
- [ ] Redis miss 再查 DB
- [ ] 空值缓存防穿透
- [ ] TTL 带随机抖动
- [ ] 热点数据有击穿保护
- [ ] Caffeine 设置 maximumSize
- [ ] 多实例缓存失效采用广播，而不是竞争消费
- [ ] 更新 DB 后缓存失效发生在事务提交之后
- [ ] 删除失败存在重试/TTL 兜底

## 限流

- [ ] 自定义 `@RateLimit`
- [ ] AOP 统一拦截
- [ ] Redis ZSet
- [ ] Lua 原子判断
- [ ] 用户维度
- [ ] IP 维度（至少验证码/登录）
- [ ] 秒杀全局维度
- [ ] 被限流返回统一 429

## 订单生命周期

- [ ] CREATED / CANCELLED / EXPIRED 等状态明确
- [ ] 使用条件更新避免状态并发覆盖
- [ ] 用户只能取消自己的订单
- [ ] 取消后数据库库存回流
- [ ] 库存回流事件可重试
- [ ] Redis 库存最终回流
- [ ] 超时消息重复到达也不会重复释放库存

## 售罄订阅

- [ ] 售罄后可订阅
- [ ] 同一用户重复订阅被去重
- [ ] 可取消订阅
- [ ] 库存回流后按时间顺序处理
- [ ] 产生站内通知

## 压测

- [ ] 保存 `.jmx`
- [ ] 保存机器配置
- [ ] 保存 JVM 配置
- [ ] 保存并发数
- [ ] 保存数据规模
- [ ] 记录 P50/P95/P99
- [ ] 记录 Throughput
- [ ] 记录 Error Rate
- [ ] 验证库存不超卖
- [ ] 验证一人一单
- [ ] 简历中的所有性能数字都能复现

---

# 16. 简历写法参考

等你真正实现完成后，可以按实际情况改写。

## 项目名称

**邻享生活 —— 高并发本地生活服务平台**

## 技术栈

> Spring Boot + MyBatis-Plus + MySQL + Redis + Lua + Redisson + RabbitMQ + Caffeine + Docker + JMeter

## 项目介绍

> 面向本地生活场景的商户与优惠券服务平台，提供商户查询、优惠券秒杀、用户互动、订单管理及售罄订阅等功能；围绕秒杀削峰、消息可靠性、多级缓存一致性、流量治理与故障补偿进行了工程化升级。

## 推荐项目描述

### 1. 秒杀与 MQ

> 基于 Redis + Lua 原子完成库存校验、一人一单与库存预扣，通过 RabbitMQ 异步创建订单；结合 Publisher Confirm、Return、消费者 ACK、死信队列及数据库唯一索引处理发送失败、路由失败、重复消费与异常订单问题。

### 2. 故障补偿

> 在 Lua 秒杀链路中记录待发送订单状态，通过定时任务扫描超时 PENDING 请求并结合 MySQL 订单状态进行补偿重投，降低 Redis 预扣成功但 MQ 投递异常造成的库存与订单不一致。

### 3. 多级缓存

> 针对热门商户构建 Caffeine + Redis 两级缓存，结合空值缓存、随机 TTL、逻辑过期和互斥重建降低缓存穿透、雪崩与热点 Key 回源压力。

### 4. 缓存一致性

> 商户数据更新后采用事务提交后缓存失效策略，并通过 RabbitMQ Fanout 向各应用实例广播本地缓存失效事件，结合失败重试与 TTL 兜底实现缓存最终一致性。

### 5. 限流

> 基于 Redis ZSet + Lua + AOP + 自定义注解实现分布式滑动窗口限流，支持用户、IP 及业务资源维度，对验证码与秒杀等核心接口进行流量控制。

### 6. 订单生命周期

> 增加订单取消与超时释放机制，通过条件状态更新避免并发状态覆盖，并使用事务 Outbox 记录库存回流事件，异步恢复 Redis 库存并触发售罄订阅通知。

### 7. 业务升级

> 使用 Redis ZSet 实现优惠券售罄订阅，库存回流后按订阅时间触发到券提醒，并通过站内消息保存通知状态。

### 8. 性能

最后根据你的**真实压测结果**再加：

> 在 XXX 配置、XXX 并发下，商户查询接口 P95 由 XX ms 降至 XX ms，Redis 请求量下降 XX%，热点本地缓存命中率达到 XX%。

不要提前写假数字。

---

# 17. 面试时必须能解释的问题

完成项目后至少要能回答以下问题。

## RabbitMQ

1. 为什么不用原版 Redis Stream？
2. RabbitMQ 的 Exchange、Queue、Binding 分别是什么？
3. Direct / Topic / Fanout 有什么区别？
4. 为什么订单用 Direct，缓存失效用 Fanout？
5. Publisher Confirm 解决什么？
6. Return 解决什么？
7. ACK 和 Confirm 有什么区别？
8. Consumer 为什么不能先 ACK 再写数据库？
9. 消息重复消费怎么办？
10. DLQ 用来解决什么？
11. 为什么不能 `nack(requeue=true)` 无限重试？
12. Redis 扣库存成功但消息没发出去怎么办？
13. Pending 重试为什么还要先查询数据库？
14. RabbitMQ 挂了对秒杀有什么影响？

## Redis / 秒杀

1. Lua 为什么能保证这几步原子执行？
2. 为什么库存放 Redis？
3. 为什么 MySQL 还要做 `stock > 0` 条件更新？
4. Redis 和 MySQL 库存为什么允许短暂不一致？
5. 一人一单为什么既用 Redis 又用数据库唯一索引？
6. 如果 Redis 数据丢失怎么办？

## Caffeine

1. 为什么 Redis 前还要加 Caffeine？
2. Caffeine 是不是分布式缓存？
3. 多实例本地缓存如何失效？
4. 为什么广播失效不能让所有实例监听一个 Queue？
5. Caffeine 为什么一定要限制 maximumSize？
6. expireAfterWrite 和 refreshAfterWrite 有什么区别？
7. 哪些数据不适合放 Caffeine？

## 缓存一致性

1. 为什么更新 DB 后选择删除缓存，而不是更新缓存？
2. 为什么要事务提交后再失效？
3. 删除 Redis 失败怎么办？
4. 什么叫最终一致性？
5. 延迟双删解决的是什么竞态？
6. 为什么不能说你的方案“保证强一致”？

## 限流

1. 固定窗口有什么问题？
2. 滑动窗口怎么实现？
3. 为什么 ZSet 适合滑动窗口？
4. 为什么用 Lua？
5. Lua 中为什么可以用 Redis TIME？
6. AOP 限流的缺点是什么？
7. 如果以后有 Gateway，限流应该放哪一层？
8. Redis 挂了限流是 fail-open 还是 fail-close？为什么？

> 建议：普通查询可根据业务选择 fail-open；资金、风控等关键操作可能选择更严格策略。你的项目要明确说出自己的取舍，不需要假装存在唯一正确答案。

## 订单取消 / Outbox

1. 为什么订单状态更新要带 `WHERE status = CREATED`？
2. 为什么取消时不直接同时更新 Redis 和 MySQL？
3. Outbox Pattern 解决什么？
4. Outbox 消息发送失败怎么办？
5. Outbox Publisher 多实例重复扫描怎么办？
6. 库存回流消息重复消费怎么办？
7. 为什么取消后默认不允许再次秒杀？

## 压测

1. 用什么工具？
2. 测试环境配置？
3. 为什么只说平均响应时间不够？
4. P95、P99 是什么？
5. 如何确认没有超卖？
6. 如何确认没有重复订单？
7. 本地缓存命中率怎么统计？
8. 优化前后是否是同一套机器、同一数据、同一压测参数？

---

# 18. 官方资料

以下资料建议在实现对应模块时直接查官方文档，而不是只看二手博客。

## RabbitMQ

### Consumer Acknowledgements & Publisher Confirms

https://www.rabbitmq.com/docs/confirms

重点学习：

- publisher confirms；
- consumer acknowledgements；
- ack / nack；
- 数据安全语义。

### RabbitMQ Reliability Guide

https://www.rabbitmq.com/docs/reliability

### Dead Letter Exchanges

https://www.rabbitmq.com/docs/dlx

### Message / Queue TTL

https://www.rabbitmq.com/docs/ttl

---

## Spring AMQP

### AmqpTemplate / RabbitTemplate

https://docs.spring.io/spring-amqp/reference/amqp/template.html

### Sending Messages

https://docs.spring.io/spring-amqp/reference/amqp/sending-messages.html

### Async Consumer

https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-consumer.html

### Error Recovery

https://docs.spring.io/spring-amqp/reference/amqp/resilience-recovering-from-errors-and-broker-failures.html

---

## Redis

### Lua Scripting

https://redis.io/docs/latest/develop/programmability/eval-intro/

### Sorted Sets

https://redis.io/docs/latest/develop/data-types/sorted-sets/

### Rate Limiting

https://redis.io/tutorials/howtos/ratelimiting/

---

## Caffeine

### Eviction

https://github.com/ben-manes/caffeine/wiki/Eviction

### Refresh

https://github.com/ben-manes/caffeine/wiki/Refresh

### Population / Loading Cache

https://github.com/ben-manes/caffeine/wiki/Population

---

# 最后建议：不要一次全部改

推荐你实际开发按这个顺序：

```text
第 1 步
RabbitMQ 替换原异步队列

第 2 步
Confirm + Return + ACK + DLQ + 幂等

第 3 步
PENDING + 补偿重投

第 4 步
Caffeine + Redis

第 5 步
缓存失效广播

第 6 步
Redis Lua 滑动窗口限流

第 7 步
JMeter 第一轮压测

第 8 步
订单取消 + 超时

第 9 步
Outbox 库存回流

第 10 步
售罄订阅 / 站内通知

第 11 步
完整压测 + README + 架构图
```

做到第 7 步，你就已经得到一个**明显区别于教程原版、适合 Java 后端面试展开讲解**的项目。

做到第 11 步，则项目已经具备比较完整的：

```text
高并发入口
消息削峰
可靠消息
幂等消费
多级缓存
缓存一致性
分布式限流
订单状态机
事务事件
故障补偿
业务扩展
可压测验证
```

此时再继续盲目堆 Kafka、ES、微服务、分库分表，收益通常不如把已有这些模块真正做透、写测试、做压测并准备好面试追问。
