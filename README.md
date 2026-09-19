# lxsh-dianping

> 面向本地生活场景的高并发点评服务后端。项目提供商户查询、用户登录、探店笔记、关注互动、优惠券秒杀等能力，并围绕高并发秒杀、缓存治理、可靠消息与流量控制进行工程化实践。

## 技术栈

`Java 8` · `Spring Boot 2.7` · `MyBatis-Plus` · `MySQL` · `Redis` · `Lua` · `Caffeine` · `RabbitMQ` · `Redisson` · `Docker Compose` · `JMeter`

## 项目亮点

- **可靠异步秒杀**：Redis Lua 脚本原子完成库存校验、库存预扣和一人一单校验；随后通过 RabbitMQ 异步创建订单，降低请求线程中的数据库写压力。
- **消息可靠性与补偿**：使用 Publisher Confirm、Return、手动 ACK、重试队列、失败队列与停车队列处理消息投递和消费异常；Redis 记录待处理订单，由定时任务补偿重投，减少预扣成功但消息未落库的风险。
- **幂等与超卖保护**：消费者侧通过数据库事务、库存条件扣减及订单唯一约束，保证重复消息不会重复下单，库存不会超卖。
- **多级缓存**：商户详情采用 Caffeine + Redis + MySQL 查询链路，配合空值缓存和随机 TTL，应对缓存穿透、热点访问和缓存雪崩。
- **缓存最终一致性**：商户更新在事务提交后删除 Redis 缓存、清理本地缓存，并经 RabbitMQ Fanout 广播各实例失效本地缓存。
- **分布式限流**：基于 AOP、自定义注解、Redis ZSet 和 Lua 实现滑动窗口限流，秒杀接口支持全局维度限流。
- **本地生活能力**：支持验证码/密码登录、Token 会话、个人资料维护、签到统计、商户 GEO 查询、探店笔记、点赞排行榜、关注及共同关注。

## 架构概览

```text
                         ┌──────────────────────┐
                         │      Client/API      │
                         └──────────┬───────────┘
                                    │
              ┌─────────────────────┴──────────────────────┐
              │                                            │
              ▼                                            ▼
      商户详情、内容查询                                  秒杀下单
              │                                            │
              ▼                                            ▼
  Caffeine → Redis → MySQL                    AOP 限流 → Redis + Lua
              │                                            │
              │                                            ▼
              │                                      Redis 预扣库存
              │                                            │
              ▼                                            ▼
  更新后：事务提交 → 缓存失效广播                       RabbitMQ
              │                                            │
              ▼                                            ▼
     RabbitMQ Fanout → 各实例 Caffeine              消费者事务落库
                                                           │
                                                           ▼
                                              ACK / 重试 / 失败队列 / 补偿
```

## 核心业务链路

### 秒杀下单

```text
请求
  → 滑动窗口限流
  → Redis Lua：校验库存、一人一单、预扣库存、登记 Pending
  → RabbitMQ 发布订单消息
  → 消费者幂等写入 MySQL 订单并条件扣减数据库库存
  → 手动 ACK，并清理 Pending 记录
```

异常消息会进入重试或失败队列；待处理订单定时补偿时会先核对数据库订单状态，避免重复创建订单。

### 商户详情缓存

```text
请求 → Caffeine 本地缓存 → Redis → MySQL → 回填 Redis / Caffeine
```

商户更新后，在数据库事务提交后依次进行 Redis 和本地缓存失效，并广播通知其他应用实例清理本地缓存，TTL 作为最终兜底。

## 已实现功能

| 模块 | 功能 |
| --- | --- |
| 用户 | 手机验证码/密码登录、Token 续期与登出、个人资料、签到及连续签到统计 |
| 商户 | 商户详情、名称检索、按类型分页、基于 Redis GEO 的附近商户查询 |
| 互动 | 探店笔记发布、热门笔记、点赞与点赞排行、关注/取关、共同关注、关注流滚动分页 |
| 优惠券 | 普通优惠券、秒杀券、异步下单状态查询 |
| 缓存 | Caffeine + Redis 二级缓存、空值缓存、随机过期时间、缓存失效广播 |
| 秒杀保障 | Lua 原子校验、库存预扣、一人一单、异步落库、Pending 补偿、重试/失败队列、幂等消费 |
| 流量治理 | Redis Lua 滑动窗口限流；默认秒杀入口为 `300 请求/秒` |

## 快速开始

### 1. 环境要求

- JDK 8+
- Maven 3.6+
- MySQL 5.7+（或兼容版本）
- Redis 6+
- RabbitMQ 4.3+（项目已在 `rabbitmq:4.3.4-management` 配置下使用）

### 2. 获取代码并进入 Maven 模块

```bash
git clone <your-repository-url> lxsh-dianping
cd lxsh-dianping/hm-dianping
```

> 仓库目录当前为 `hm-dianping`，用于兼容已有 Maven 模块；GitHub 项目名称与 README 均使用 **lxsh-dianping**。

### 3. 初始化数据库

创建数据库并导入初始表与示例数据：

```bash
mysql -u root -p -e "CREATE DATABASE hmdp DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
```

根据自己的 MySQL 账号创建或调整应用数据库用户。

### 4. 配置环境变量

从模板创建本地配置：

```bash
copy .env.example .env
```

macOS/Linux：

```bash
cp .env.example .env
```

编辑 `.env`，至少确认以下配置与本地环境一致：

```properties
HMDP_DATASOURCE_URL=jdbc:mysql://localhost:3306/hmdp?useSSL=false&serverTimezone=UTC
HMDP_DATASOURCE_USERNAME=hmdp_user
HMDP_DATASOURCE_PASSWORD=change-me

HMDP_REDIS_HOST=localhost
HMDP_REDIS_PORT=6379
HMDP_REDIS_PASSWORD=change-me

HMDP_RABBITMQ_HOST=localhost
HMDP_RABBITMQ_PORT=5672
HMDP_RABBITMQ_USERNAME=hmdp
HMDP_RABBITMQ_PASSWORD=hmdp_mq_2026
HMDP_RABBITMQ_VIRTUAL_HOST=/hmdp
```

`.env` 已被 Git 忽略，请勿提交真实密码、Token 或私有地址。

### 5. 启动 Redis 与 RabbitMQ

项目提供的 Compose 文件位于 `src/main/resources/docker-compose.yml`，可启动 RabbitMQ 管理端和两个供 Redisson 测试使用的 Redis 节点：

```bash
docker compose --env-file .env -f src/main/resources/docker-compose.yml up -d
```

当前应用默认连接 `localhost:6379` 的主 Redis；请自行启动该实例，或将 `HMDP_REDIS_PORT` 改为实际端口。默认 `hmdp.redisson.enabled=false`，因此 Compose 中的 `6381`、`6382` 节点不是正常启动的必需条件。

RabbitMQ 管理页默认地址为 `http://localhost:15672`。

### 6. 启动应用

```bash
mvn spring-boot:run
```

服务默认监听 `http://localhost:8081`。

打包运行方式：

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

## 关键配置

配置文件为 [`application.yaml`](hm-dianping/src/main/resources/application.yaml)。常用开关如下：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `hmdp.cache.shop.local-enabled` | `true` | 是否启用商户 Caffeine 本地缓存 |
| `hmdp.seckill.async-enabled` | `true` | `true` 为 RabbitMQ 异步下单；`false` 为同步写库，便于对比压测 |
| `hmdp.rate-limit.enabled` | `true` | 是否启用限流 |
| `hmdp.rate-limit.seckill.max-count` | `300` | 秒杀窗口最大放行数 |
| `hmdp.rate-limit.seckill.window-seconds` | `1` | 秒杀限流窗口（秒） |
| `hmdp.redisson.enabled` | `false` | 是否启用 Redisson 多节点配置 |
| `hmdp.id-generator.worker-id` | `1` | Snowflake worker ID；多实例部署时必须区分 |

## 常用接口

所有接口统一返回 `Result`，需要登录的接口携带 `authorization` 请求头。

| 模块 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| 用户 | `POST` | `/user/code?phone={phone}` | 发送验证码 |
| 用户 | `POST` | `/user/login` | 验证码或密码登录 |
| 用户 | `POST` | `/user/logout` | 登出 |
| 用户 | `POST` | `/user/sign` | 签到 |
| 商户 | `GET` | `/shop/{id}` | 查询商户详情 |
| 商户 | `GET` | `/shop/of/type` | 按类型/GEO 分页查询 |
| 笔记 | `POST` | `/blog` | 发布探店笔记 |
| 笔记 | `PUT` | `/blog/like/{id}` | 点赞或取消点赞 |
| 笔记 | `GET` | `/blog/of/follow` | 关注流滚动分页 |
| 关注 | `PUT` | `/follow/{id}/{isFollow}` | 关注或取关 |
| 秒杀 | `POST` | `/voucher-order/seckill/{id}` | 提交秒杀请求 |
| 秒杀 | `GET` | `/voucher-order/status/{orderId}?voucherId={id}` | 查询异步订单处理状态 |

完整接口以 `src/main/java/com/hmdp/controller` 中的控制器为准。

## 压测结果

所有性能结论均来自仓库内保留的 JMeter 场景和报告；环境条件会显著影响结果，建议在自己的环境中复测。

### RabbitMQ 异步下单对比

在 1,000 用户、1 秒内抢购 1,000 库存，且关闭入口限流的场景下：

| 模式 | 平均响应 | P95 | P99 | 吞吐量 |
| --- | ---: | ---: | ---: | ---: |
| 同步下单 | 345ms | 619ms | 630ms | 591.4 req/s |
| RabbitMQ 异步下单 | 10ms | 23ms | 28ms | 933.7 req/s |

异步方式将 MySQL 写入移出请求线程，秒杀接口平均响应降低 335ms，吞吐量提升 342.3 req/s。详情见 [秒杀业务压测报告](hm-dianping/docs/秒杀业务压测报告.md)。

### Caffeine + Redis 热点缓存

对 `GET /shop/1` 进行 1,000 线程、100,000 次请求的热点读测试：

| 场景 | P95 | P99 | Redis GET 增量 |
| --- | ---: | ---: | ---: |
| 关闭 Caffeine | 3ms | 4ms | 约 100,003 次 |
| 开启 Caffeine | 1ms | 1ms | 约 2 次 |

本地缓存的主要收益是降低 Redis 热点访问和尾部延迟；当前环境中的吞吐量仅小幅变化。详情见 [二级缓存压测报告](hm-dianping/docs/Caffeine_Redis二级缓存压测报告.md)。

### 秒杀端到端基准

在 1,000 测试用户、20 秒爬坡、100ms 轮询的本机测试中，订单从提交到查询到 `COMPLETED` 的端到端平均耗时为 266.80ms，P95/P99 分别为 1424ms/2124ms。详见 [端到端压测说明](hm-dianping/docs/jmeter/README.md)。

## 测试与压测

运行单元测试：

```bash
mvn test
```

秒杀端到端 JMeter 脚本位于：

```text
hm-dianping/docs/jmeter/seckill-order-e2e.jmx
```

压测前可通过项目中的测试类准备专用测试券、测试用户和登录 Token。完整的准备命令、执行命令和一致性检查步骤请参阅 [JMeter 使用说明](hm-dianping/docs/jmeter/README.md)。压测请使用独立的券和测试数据，避免清理日常开发数据。

## 项目结构

```text
lxsh-dianping/
├── hm-dianping/                         # Spring Boot Maven 模块
│   ├── src/main/java/com/hmdp/
│   │   ├── cache/                        # Caffeine + Redis 缓存
│   │   ├── config/                       # Web、MQ、Redisson、线程池配置
│   │   ├── controller/                   # REST API
│   │   ├── mq/                           # RabbitMQ 消息模型、生产与消费、补偿
│   │   ├── ratelimit/                    # 注解式 Lua 滑动窗口限流
│   │   ├── service/                      # 业务服务
│   │   └── utils/                        # Token、缓存、锁、Snowflake ID 等工具
│   ├── src/main/resources/
│   │   ├── db/hmdp.sql                   # 数据库初始化脚本
│   │   ├── *.lua                         # 秒杀、补偿、限流 Lua 脚本
│   │   ├── application.yaml              # 应用配置
│   │   └── docker-compose.yml            # RabbitMQ / Redis 辅助环境
│   └── docs/                             # 设计、压测与 JMeter 文档
└── README.md
```

## 相关文档

- [秒杀业务压测报告](hm-dianping/docs/秒杀业务压测报告.md)
- [Caffeine + Redis 二级缓存压测报告](hm-dianping/docs/Caffeine_Redis二级缓存压测报告.md)
- [JMeter 秒杀订单端到端压测说明](hm-dianping/docs/jmeter/README.md)
- [RabbitMQ 秒杀重构说明](hm-dianping/docs/rabbitmq-seckill-rebuild-guide.md)
- [RabbitMQ 可靠性完善说明](hm-dianping/docs/rabbitmq-reliability-completion-guide.md)
- [Snowflake ID 生成器迁移说明](hm-dianping/docs/snowflake-id-generator-migration.md)

## 注意事项

- 本项目为后端服务，前端资源不在当前仓库内。
- 生产环境请通过环境变量或密钥管理服务提供数据库、Redis 与 RabbitMQ 凭据，避免使用示例密码。
- 多实例部署时为每个实例配置不同的 `hmdp.instance-id`、`worker-id`，并按实际情况设计 Redis/RabbitMQ 高可用方案。
- 运行压测或数据准备测试前，请确认使用专用数据库记录和秒杀券；相关测试会重置指定券的库存及订单数据。

## License

本项目当前未声明开源许可证。如需对外开源，请在发布前补充合适的 `LICENSE` 文件。
