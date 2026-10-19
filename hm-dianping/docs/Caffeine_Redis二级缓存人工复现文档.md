# Caffeine + Redis 二级缓存人工复现文档

本文档基于当前 `hm-dianping` 项目结构，说明如何按“Caffeine 本地缓存 + Redis 分布式缓存 + MySQL 数据库”的方式改造商户详情查询，并补上多实例本地缓存失效广播。

目标链路：

```text
GET /shop/{id}
    -> Caffeine 本地缓存
    -> Redis 分布式缓存
    -> MySQL
```

更新链路：

```text
PUT /shop
    -> 更新 MySQL
    -> 事务提交后删除 Redis
    -> 删除本实例 Caffeine
    -> RabbitMQ Fanout 广播
    -> 其他实例删除自己的 Caffeine
```

## 1. 为什么要做二级缓存

原项目的商户详情查询主要使用 Redis 缓存：

```text
Application
    -> Redis
    -> MySQL
```

Redis 已经能减少 MySQL 压力，但高并发热点商户场景下，请求仍然会频繁访问 Redis。Redis 虽快，但仍然要走网络 IO、序列化和反序列化。

加入 Caffeine 后：

```text
Application
    -> Caffeine Local Cache
    -> Redis
    -> MySQL
```

Caffeine 位于当前 Java 应用 JVM 内部，命中时就是本机内存读取，不需要网络请求。它的主要作用是：

- 降低 Redis QPS，避免热门商户把 Redis 打成热点。
- 缩短热点查询接口响应时间。
- 在 Redis 短暂抖动时提供一层本地缓冲。
- 与 Redis、MySQL 共同构成多级缓存，提升读多写少接口的抗压能力。

适合放入 Caffeine 的数据：

- 热门商户详情。
- 商户类型列表。
- 热门榜单。
- 更新频率低、允许短时间旧值的数据。

不适合放入 Caffeine 的数据：

- 秒杀实时库存。
- 用户订单实时状态。
- 余额、强实时计数器。
- 高频写入且不允许短暂不一致的数据。

核心判断标准：**读多写少、热点明显、允许几十秒以内的短暂旧值**。

## 2. 当前项目中的改造落点

当前项目相关文件：

```text
pom.xml
src/main/resources/application.yaml
src/main/java/com/hmdp/utils/RedisConstants.java
src/main/java/com/hmdp/mq/RabbitMqConstants.java
src/main/java/com/hmdp/config/RabbitMqConfig.java
src/main/java/com/hmdp/service/impl/ShopServiceImpl.java
```

建议新增文件：

```text
src/main/java/com/hmdp/config/LocalCacheConfig.java
src/main/java/com/hmdp/cache/ShopCacheService.java
src/main/java/com/hmdp/mq/CacheInvalidateMessage.java
src/main/java/com/hmdp/mq/CacheInvalidatePublisher.java
src/main/java/com/hmdp/mq/CacheInvalidateConsumer.java
```

为什么单独新增 `ShopCacheService`：

- 避免把复杂缓存逻辑继续堆到 `ShopServiceImpl`。
- 后续如果商户类型、榜单也要接入本地缓存，可以复用设计思路。
- 查询、回填、失效逻辑聚合在一个地方，更容易压测和排查。

## 3. 添加 Caffeine 依赖

当前项目使用 Java 8 和 Spring Boot 2.7.3，建议使用 Caffeine 2.9.x。

在 `pom.xml` 中加入：

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>2.9.3</version>
</dependency>
```

不要直接使用 Caffeine 3.x。Caffeine 3.x 面向更高 Java 版本，当前项目 Java 8 下容易出现编译或运行兼容问题。

## 4. 增加本地缓存配置

新增：

```text
src/main/java/com/hmdp/config/LocalCacheConfig.java
```

代码：

```java
package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class LocalCacheConfig {

    @Bean
    public Cache<Long, Shop> shopLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
```

这段代码的作用：

- `Cache<Long, Shop>`：缓存商户 id 到商户详情对象。
- `maximumSize(10_000)`：限制最多缓存 10000 个商户，防止 JVM 内存被无限占用。
- `expireAfterWrite(60, TimeUnit.SECONDS)`：本地缓存写入 60 秒后过期，降低旧数据停留时间。
- `recordStats()`：开启统计，可以查看命中率、未命中次数、淘汰次数。

为什么必须设置 `maximumSize`：

Caffeine 是 JVM 内存缓存，如果不限制容量，商户 id 不断增加时，本地缓存会持续吃堆内存，严重时触发频繁 GC 或 OOM。

参数不是固定答案。后续应根据这些因素调整：

```text
商户总量
单个 Shop 对象大小
JVM 堆大小
访问热点集中程度
商户更新频率
可接受的旧数据时间
```

## 5. Redis TTL 增加随机抖动

商户缓存如果统一设置 30 分钟过期，大量 key 可能在同一时间失效，形成缓存雪崩。

建议使用：

```text
基础 TTL + 随机值
```

例如普通商户：

```text
Caffeine TTL：60 秒
Redis TTL：30 到 40 分钟
```

Caffeine 时间短，是为了缩短本地旧值窗口；Redis 时间长，是为了减少 MySQL 回源。

## 6. 新增 ShopCacheService

新增：

```text
src/main/java/com/hmdp/cache/ShopCacheService.java
```

代码：

```java
package com.hmdp.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Slf4j
@Service
public class ShopCacheService {

    @Resource
    private Cache<Long, Shop> shopLocalCache;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Shop queryShopById(Long id, Function<Long, Shop> dbFallback) {
        return shopLocalCache.get(id, shopId -> queryRedisAndDb(shopId, dbFallback));
    }

    private Shop queryRedisAndDb(Long id, Function<Long, Shop> dbFallback) {
        String redisKey = CACHE_SHOP_KEY + id;

        String json = stringRedisTemplate.opsForValue().get(redisKey);

        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, Shop.class);
        }

        if (json != null) {
            return null;
        }

        Shop shop = dbFallback.apply(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(
                    redisKey,
                    "",
                    CACHE_NULL_TTL,
                    TimeUnit.MINUTES
            );
            return null;
        }

        stringRedisTemplate.opsForValue().set(
                redisKey,
                JSONUtil.toJsonStr(shop),
                randomShopCacheTtlMinutes(),
                TimeUnit.MINUTES
        );

        return shop;
    }

    public void invalidateLocal(Long id) {
        shopLocalCache.invalidate(id);
    }

    public void invalidateAllLocal() {
        shopLocalCache.invalidateAll();
    }

    private long randomShopCacheTtlMinutes() {
        return CACHE_SHOP_TTL + ThreadLocalRandom.current().nextLong(0, 11);
    }
}
```

### 6.1 查询流程解释

入口方法：

```java
public Shop queryShopById(Long id, Function<Long, Shop> dbFallback)
```

这里没有直接注入 `ShopMapper` 或 `IShopService`，而是接收一个 `dbFallback` 函数。调用方可以传入：

```java
this::getById
```

这样 `ShopCacheService` 只负责缓存逻辑，不负责具体数据库查询细节。

### 6.2 为什么用 `shopLocalCache.get(id, loader)`

```java
return shopLocalCache.get(id, shopId -> queryRedisAndDb(shopId, dbFallback));
```

这个写法有两个好处：

- 如果 Caffeine 命中，直接返回，不查 Redis。
- 如果同一个 JVM 内多个线程同时查同一个未命中的 key，Caffeine 会尽量合并 loader 加载，减少同一实例内重复回源。

注意：Caffeine 不能缓存 `null`。当商户不存在时，Redis 会缓存空字符串防穿透，但 Caffeine 不缓存空值。这样设计更安全，避免本地缓存长期保存不存在状态。

### 6.3 Redis 命中逻辑

```java
if (StrUtil.isNotBlank(json)) {
    return JSONUtil.toBean(json, Shop.class);
}
```

Redis 中有正常 JSON，说明二级缓存命中。此时返回对象后，Caffeine 会自动把这个返回值放入本地缓存。

### 6.4 空值缓存逻辑

```java
if (json != null) {
    return null;
}
```

`json != null` 但 `StrUtil.isNotBlank(json)` 为 false，说明 Redis 中存的是空字符串 `""`。

这是缓存穿透保护。恶意或异常请求不断查询不存在的商户 id 时，不会每次都查 MySQL，而是短时间内由 Redis 空值挡住。

### 6.5 Redis 回填逻辑

```java
stringRedisTemplate.opsForValue().set(
        redisKey,
        JSONUtil.toJsonStr(shop),
        randomShopCacheTtlMinutes(),
        TimeUnit.MINUTES
);
```

这里把 MySQL 查询结果写入 Redis，同时使用随机 TTL。

为什么 Redis TTL 比 Caffeine 长：

- Redis 是跨实例共享缓存，适合保存更长时间。
- Caffeine 是每个 JVM 自己的一份副本，越长越容易出现多实例旧值，所以本地 TTL 要短。

## 7. 改造 ShopServiceImpl 查询逻辑

修改：

```text
src/main/java/com/hmdp/service/impl/ShopServiceImpl.java
```

新增字段：

```java
@Resource
private ShopCacheService shopCacheService;
```

需要新增 import：

```java
import com.hmdp.cache.ShopCacheService;
```

把原来的 `queryById` 改成：

```java
@Override
public Result queryById(Long id) {
    Shop shop = shopCacheService.queryShopById(id, this::getById);

    if (shop == null) {
        return Result.fail("商铺不存在");
    }

    return Result.ok(shop);
}
```

这段代码的作用：

- `ShopServiceImpl` 仍然负责业务编排和返回 `Result`。
- 具体缓存查询逻辑交给 `ShopCacheService`。
- 数据库兜底查询仍然复用 MyBatis-Plus 的 `getById`。

切换后，不要再同时使用原来的：

```java
cacheClient.queryWithLogicalExpire(...)
```

否则同一个 Redis key 可能一会儿存普通 `Shop` JSON，一会儿存带 `expireTime` 的 `RedisData` JSON，反序列化结构会混乱。

如果之前已经用逻辑过期方案预热过 `cache:shop:*`，切换前建议清理旧商户缓存：

```text
redis-cli
DEL cache:shop:1
```

批量清理时可以按自己的 Redis 工具执行 `SCAN + DEL`，不要在生产环境直接使用 `KEYS cache:shop:*`。

## 8. 添加缓存失效消息常量

修改：

```text
src/main/java/com/hmdp/mq/RabbitMqConstants.java
```

新增：

```java
/**
 * 缓存失效广播交换机和实例队列前缀。
 */
public static final String CACHE_INVALIDATE_EXCHANGE =
        "hmdp.cache.invalidate.exchange";

public static final String CACHE_INVALIDATE_QUEUE_PREFIX =
        "hmdp.cache.invalidate.";
```

这里不需要 routing key，因为 Fanout Exchange 会把消息广播给所有绑定队列。

## 9. 配置每个实例自己的缓存失效队列

修改：

```text
src/main/java/com/hmdp/config/RabbitMqConfig.java
```

新增 import：

```java
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
```

新增 Bean：

```java
@Bean
public FanoutExchange cacheInvalidateExchange() {
    return new FanoutExchange(
            CACHE_INVALIDATE_EXCHANGE,
            true,
            false
    );
}

@Bean
public Queue cacheInvalidateQueue(
        @Value("${hmdp.instance-id:${spring.application.name}-${server.port}}")
        String instanceId) {
    return QueueBuilder
            .nonDurable(CACHE_INVALIDATE_QUEUE_PREFIX + instanceId)
            .autoDelete()
            .build();
}

@Bean
public Binding cacheInvalidateBinding(
        @Qualifier("cacheInvalidateQueue") Queue cacheInvalidateQueue,
        @Qualifier("cacheInvalidateExchange") FanoutExchange cacheInvalidateExchange) {
    return BindingBuilder
            .bind(cacheInvalidateQueue)
            .to(cacheInvalidateExchange);
}
```

这段配置的作用：

- `cacheInvalidateExchange()`：创建一个 Fanout 交换机。
- `cacheInvalidateQueue()`：每个应用实例创建自己的队列。
- `cacheInvalidateBinding()`：把当前实例队列绑定到 Fanout 交换机。

为什么不用所有实例监听同一个队列：

RabbitMQ 中一个普通队列如果有多个消费者，同一条消息通常只会交给其中一个消费者，这叫竞争消费。但本地缓存失效要求每个实例都收到消息，所以必须是：

```text
Fanout Exchange
    -> app-1 queue
    -> app-2 queue
    -> app-3 queue
```

而不是：

```text
one queue
    -> app-1 / app-2 / app-3 竞争消费
```

为什么队列可以 `nonDurable + autoDelete`：

Caffeine 在 JVM 内存中，实例重启后本地缓存自然丢失，所以实例级失效队列通常不需要持久化。实例下线后队列自动删除即可。

如果你的部署环境要求实例重启后仍保留固定队列，也可以改成 durable queue，但要确保不同实例队列名不冲突。

## 10. 配置实例 id

修改：

```text
src/main/resources/application.yaml
```

在已有 `hmdp` 节点下加入：

```yaml
hmdp:
  instance-id: app-1
  id-generator:
    worker-id: 1
    datacenter-id: 1
```

如果你本来已经有 `hmdp.id-generator`，不要重复写两个 `hmdp` 根节点，合并即可。

多实例启动时必须设置不同实例 id：

```text
app-1
app-2
app-3
```

例如本地启动两个实例：

```bash
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --server.port=8081 --hmdp.instance-id=app-1
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --server.port=8082 --hmdp.instance-id=app-2
```

如果两个实例使用相同队列名，就会退化成竞争消费，缓存失效广播会失效。

## 11. 新增缓存失效消息对象

新增：

```text
src/main/java/com/hmdp/mq/CacheInvalidateMessage.java
```

代码：

```java
package com.hmdp.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheInvalidateMessage {

    private String cacheName;

    private String key;
}
```

字段含义：

- `cacheName`：缓存名称，例如 `shop`。
- `key`：业务 key，例如商户 id `1001`。

为什么不直接发 Redis key：

发送业务语义更清晰。消费者可以根据 `cacheName` 决定清理哪类本地缓存。例如以后扩展：

```text
shop
shopType
hotBlog
```

## 12. 新增缓存失效发布器

新增：

```text
src/main/java/com/hmdp/mq/CacheInvalidatePublisher.java
```

代码：

```java
package com.hmdp.mq;

import com.hmdp.cache.ShopCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;

import static com.hmdp.mq.RabbitMqConstants.CACHE_INVALIDATE_EXCHANGE;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheInvalidatePublisher {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopCacheService shopCacheService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    public void invalidateShopAfterCommit(Long shopId) {
        Runnable task = () -> invalidateShopNow(shopId);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    }
            );
            return;
        }

        task.run();
    }

    private void invalidateShopNow(Long shopId) {
        String redisKey = CACHE_SHOP_KEY + shopId;

        stringRedisTemplate.delete(redisKey);
        shopCacheService.invalidateLocal(shopId);

        CacheInvalidateMessage message =
                new CacheInvalidateMessage("shop", String.valueOf(shopId));

        rabbitTemplate.convertAndSend(
                CACHE_INVALIDATE_EXCHANGE,
                "",
                message
        );

        log.info("商户缓存失效消息已发布，shopId={}", shopId);
    }
}
```

### 12.1 为什么要 afterCommit

更新商户时，不能在数据库事务还没提交时就通知其他实例清缓存。

错误场景：

```text
1. 事务中 UPDATE MySQL
2. 立刻删除缓存并广播
3. 其他实例收到消息后回源 MySQL
4. 当前事务最后回滚
```

其他实例可能加载到错误或中间状态。

所以这里使用：

```java
TransactionSynchronizationManager.registerSynchronization(...)
```

把缓存失效动作放到事务提交成功之后执行。

如果当前方法没有事务，则直接执行失效动作。

### 12.2 为什么删除缓存而不是更新缓存

更新 DB 后如果同步更新这些地方：

```text
Redis
Caffeine app-1
Caffeine app-2
Caffeine app-3
```

写路径会很复杂，而且任何一步失败都可能造成不一致。

更常见的 Cache Aside 设计是：

```text
更新 DB
    -> 删除缓存
    -> 下一次查询重新加载
```

目标不是强一致，而是让旧副本尽快失效，最终由 TTL 和重新查询兜底。

## 13. 改造 ShopServiceImpl 更新逻辑

修改：

```text
src/main/java/com/hmdp/service/impl/ShopServiceImpl.java
```

新增字段：

```java
@Resource
private CacheInvalidatePublisher cacheInvalidatePublisher;
```

新增 import：

```java
import com.hmdp.mq.CacheInvalidatePublisher;
```

把更新方法改成：

```java
@Override
@Transactional
public Result update(Shop shop) {
    Long id = shop.getId();
    if (id == null) {
        return Result.fail("商铺id不能为空");
    }

    updateById(shop);

    cacheInvalidatePublisher.invalidateShopAfterCommit(id);

    return Result.ok();
}
```

这段代码的作用：

- `updateById(shop)`：只负责修改 MySQL。
- `invalidateShopAfterCommit(id)`：事务提交后统一处理 Redis 删除、本机 Caffeine 删除、MQ 广播。
- `@Transactional`：保证数据库更新成功提交后才执行失效动作。

最终顺序是：

```text
1. 开启事务
2. 更新 MySQL
3. 提交事务
4. 删除 Redis
5. 删除本机 Caffeine
6. 发送 Fanout 广播
7. 其他实例删除自己的 Caffeine
```

## 14. 新增缓存失效消费者

新增：

```text
src/main/java/com/hmdp/mq/CacheInvalidateConsumer.java
```

代码：

```java
package com.hmdp.mq;

import com.hmdp.cache.ShopCacheService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheInvalidateConsumer {

    @Resource
    private ShopCacheService shopCacheService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = "#{cacheInvalidateQueue.name}")
    public void onCacheInvalidate(
            CacheInvalidateMessage cacheMessage,
            Message message,
            Channel channel) throws IOException {

        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            if (!"shop".equals(cacheMessage.getCacheName())) {
                log.warn("忽略未知缓存失效消息，message={}", cacheMessage);
                channel.basicAck(deliveryTag, false);
                return;
            }

            Long shopId = Long.valueOf(cacheMessage.getKey());

            shopCacheService.invalidateLocal(shopId);
            stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);

            channel.basicAck(deliveryTag, false);
            log.info("商户缓存失效完成，shopId={}", shopId);
        } catch (Exception e) {
            log.error("商户缓存失效失败，message={}", cacheMessage, e);
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
```

这段代码的作用：

- 监听当前实例自己的缓存失效队列。
- 收到 `shop` 缓存失效消息后删除本实例 Caffeine。
- 再删除一次 Redis，作为发布端 Redis 删除失败时的补偿。
- 手动 ACK，保证处理成功后才确认消息。

为什么消费者也删除 Redis：

发布器已经同步删除 Redis。消费者再删一次是幂等补偿：

```text
delete cache:shop:1001
delete cache:shop:1001
```

重复删除不会产生错误，但可以降低发布端删除失败导致旧 Redis 缓存残留的概率。

注意：当前项目 `application.yaml` 中 RabbitMQ listener 是手动确认：

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: manual
```

所以新消费者也必须 `basicAck` 或 `basicNack`，否则消息会一直处于未确认状态。

## 15. 普通商户和极热点商户的策略区别

建议先把普通商户详情接入本文档的简单 TTL 模式：

```text
Caffeine：60 秒
Redis：30 到 40 分钟随机 TTL
MySQL：最终回源
```

极热点商户可以继续使用逻辑过期 + 异步重建：

```text
Redis 数据本身不过期或长 TTL
JSON 中携带 logicalExpireTime
逻辑过期后先返回旧数据
后台线程抢锁重建缓存
```

但是不要让同一个 Redis key 同时混用两种结构：

普通 TTL 模式：

```json
{
  "id": 1,
  "name": "xxx"
}
```

逻辑过期模式：

```json
{
  "data": {
    "id": 1,
    "name": "xxx"
  },
  "expireTime": "2026-08-12T10:00:00"
}
```

如果要区分普通商户和极热点商户，建议使用不同 key 前缀，例如：

```text
cache:shop:{id}
cache:hot-shop:{id}
```

或者在代码中明确只对部分商户走逻辑过期策略。

## 16. 防止缓存击穿的两层设计

单实例内：

```java
shopLocalCache.get(id, loader)
```

可以减少同一个 JVM 内对同一个 key 的重复加载。

多实例下：

```text
App-A Caffeine miss
App-B Caffeine miss
App-C Caffeine miss
Redis 也刚好 miss
```

多个实例仍可能同时回源 MySQL。

如果压测发现热点 key 在 Redis 过期瞬间仍会打爆 MySQL，需要再加 Redis 分布式锁：

```text
lock:shop:{id}
```

实现方式可以复用当前项目已有的 `CacheClient.queryWithLogicalExpire` 或互斥锁思路。本文档的基础版先完成二级缓存和广播失效，热点增强可以在压测后再加。

## 17. 验证步骤

### 17.1 单实例验证查询链路

1. 启动 MySQL、Redis、RabbitMQ。
2. 启动应用。
3. 清理某个商户 Redis 缓存，例如 `cache:shop:1`。
4. 第一次请求：

```http
GET http://localhost:8081/shop/1
```

预期：

```text
Caffeine miss
Redis miss
MySQL 查询
Redis 写入 cache:shop:1
Caffeine 写入 shopId=1
```

5. 第二次请求同一个接口。

预期：

```text
Caffeine 命中
不查 Redis
不查 MySQL
```

### 17.2 验证 Redis 空值防穿透

请求一个不存在的商户 id：

```http
GET http://localhost:8081/shop/99999999
```

预期：

```text
MySQL 查不到
Redis 写入 cache:shop:99999999 = ""
TTL 为 CACHE_NULL_TTL
Caffeine 不缓存 null
```

短时间内再次请求同一个不存在 id：

```text
Redis 命中空字符串
不查 MySQL
```

### 17.3 验证更新失效

先查询商户：

```http
GET http://localhost:8081/shop/1
```

再更新商户：

```http
PUT http://localhost:8081/shop
Content-Type: application/json

{
  "id": 1,
  "name": "更新后的商户名"
}
```

预期：

```text
MySQL 更新成功
事务提交后删除 Redis cache:shop:1
删除本机 Caffeine shopId=1
发送缓存失效广播
```

再次查询：

```text
Caffeine miss
Redis miss
MySQL 查询新值
重新写入 Redis 和 Caffeine
```

### 17.4 多实例验证广播

启动两个实例：

```bash
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --server.port=8081 --hmdp.instance-id=app-1
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --server.port=8082 --hmdp.instance-id=app-2
```

分别请求：

```http
GET http://localhost:8081/shop/1
GET http://localhost:8082/shop/1
```

让两个实例的 Caffeine 都缓存商户 1。

然后更新：

```http
PUT http://localhost:8081/shop
```

预期：

```text
app-1 删除自己的 Caffeine
app-1 发送 Fanout 广播
app-1 收到广播后再次删除本地 Caffeine
app-2 收到广播后删除本地 Caffeine
```

再访问 app-2：

```http
GET http://localhost:8082/shop/1
```

预期 app-2 不再返回旧的本地缓存，而是重新从 Redis 或 MySQL 加载。

## 18. 常见问题

### 18.1 为什么不是只用 Redis

Redis 是分布式缓存，适合多实例共享。但热点读非常高时，每次都访问 Redis 仍有网络成本。Caffeine 可以把最热的一小部分请求挡在 JVM 内。

### 18.2 Caffeine 是分布式缓存吗

不是。每个应用实例都有自己独立的 Caffeine。

```text
app-1 Caffeine
app-2 Caffeine
app-3 Caffeine
```

它们互相看不见，所以更新数据时必须做本地缓存失效广播。

### 18.3 能不能保证 MySQL、Redis、Caffeine 强一致

不能，也不应该这样表述。

这套方案追求的是最终一致性：

```text
事务提交后缓存失效
MQ 广播
失败重试
TTL 兜底
必要时延迟双删
```

短时间内可能存在旧值窗口。Caffeine TTL 设置得越短，旧值窗口越短，但 Redis 压力会相应增加。

### 18.4 为什么更新时删除缓存而不是更新缓存

因为需要同时更新 Redis 和多个实例的 Caffeine，写路径太复杂。删除缓存更简单，下一次查询自然回源并重建。

### 18.5 为什么本地缓存 TTL 要短

Caffeine 是每个实例独立副本。TTL 越长，多实例间旧数据存在的时间越长。商户详情这种数据可以允许短暂旧值，但不能无限旧。

### 18.6 为什么 Redis TTL 要加随机值

避免大量 key 同一时刻过期，降低缓存雪崩概率。

### 18.7 为什么不能把秒杀库存放 Caffeine

秒杀库存是强并发写数据。每个实例缓存一份库存会导致各实例看到的库存不一致，很容易超卖。库存扣减应该放在 Redis 原子脚本、数据库乐观锁或消息队列流程中处理。

## 19. 推荐简历表述

可以写：

```text
针对热门商户详情构建 Caffeine + Redis 二级缓存。查询时优先读取 JVM 本地 Caffeine，未命中再查 Redis，Redis 未命中回源 MySQL，并结合空值缓存和随机 TTL 降低缓存穿透与雪崩风险。商户更新后采用事务提交后缓存失效策略，通过 RabbitMQ Fanout 向各应用实例广播本地缓存失效事件，结合 TTL 兜底实现缓存最终一致性。
```

不要写：

```text
保证 MySQL、Redis、Caffeine 强一致。
```

更准确的说法是：

```text
通过事务提交后失效、MQ 广播、失败重试和 TTL 兜底缩短不一致窗口，实现最终一致性。
```

## 20. 人工复现检查清单

- [ ] `pom.xml` 添加 Caffeine 2.9.3 依赖。
- [ ] 新增 `LocalCacheConfig`，配置 `shopLocalCache`。
- [ ] 新增 `ShopCacheService`，实现 Caffeine -> Redis -> MySQL 查询链路。
- [ ] `ShopServiceImpl.queryById` 改为调用 `ShopCacheService`。
- [ ] 不再混用旧的 `cacheClient.queryWithLogicalExpire` 和普通 Shop JSON。
- [ ] Redis 商户缓存 TTL 增加随机抖动。
- [ ] 新增缓存失效 MQ 常量。
- [ ] `RabbitMqConfig` 新增 Fanout Exchange、每实例独立 Queue、Binding。
- [ ] `application.yaml` 配置不同实例的 `hmdp.instance-id`。
- [ ] 新增 `CacheInvalidateMessage`。
- [ ] 新增 `CacheInvalidatePublisher`，事务提交后删除 Redis、本地 Caffeine 并广播。
- [ ] `ShopServiceImpl.update` 改为调用 `invalidateShopAfterCommit`。
- [ ] 新增 `CacheInvalidateConsumer`，每个实例收到广播后删除本地 Caffeine。
- [ ] 单实例验证 Caffeine 命中。
- [ ] 验证 Redis 空值缓存。
- [ ] 多实例验证 Fanout 广播失效。
