# 阿里云短信验证码接入指南

本文介绍普通短信服务（`HMDP_SMS_PROVIDER=aliyun`）。没有企业资质的个人项目应使用已支持的短信认证模式 `aliyun-pnvs`，请直接阅读 [个人短信认证接入步骤](aliyun-pnvs-local-setup.md)。两种产品的接口、签名和模板配置分别管理。

`POST /user/code?phone=...` 已支持调用阿里云短信 `SendSms`。默认 `HMDP_SMS_PROVIDER=mock`，适合本地开发和自动化测试；切换为 `aliyun` 后才会发起真实发送请求。验证码仍为 6 位数字，存于 Redis `login:code:<phone>`，有效期 2 分钟，登录接口不变。

## 1. 准备阿里云账号和短信资源

1. 在[短信服务控制台](https://dysms.console.aliyun.com/)开通短信服务，确认账号具备发送额度或可用余额。
2. 确认账号能申请国内短信资源。阿里云当前要求国内短信签名具备企业资质；个人认证账号需要提供可用的企业授权资质。没有这类资质时，先查看官方的短信认证产品选型说明，不要假设个人实名就能申请正式签名。依据：[短信服务使用须知](https://help.aliyun.com/zh/sms/user-guide/usage-notes)。
3. 按控制台流程提交资质、申请短信签名并完成相关审核/报备，记录**签名名称**。
4. 申请验证码模板，例如“您的登录验证码为 ${code}，2分钟内有效。”，记录审核通过的 `SMS_...` 模板编号。这里的 `${code}` 是短信平台的模板变量。项目默认只传一个名为 `code` 的变量；如果你的模板变量名不同，配置 `HMDP_SMS_ALIYUN_CODE_PARAM_NAME`，不要使用带其他必填变量的模板。
5. 创建供应用使用的 RAM AccessKey，并授予发送短信权限。应用只调用 `dysms:SendSms`，可以使用以下自定义最小权限策略：

```json
{
  "Version": "1",
  "Statement": [
    { "Effect": "Allow", "Action": "dysms:SendSms", "Resource": "*" }
  ]
}
```

权限、签名、模板和返回码依据：[SendSms 接口文档](https://help.aliyun.com/zh/sms/developer-reference/api-dysmsapi-2017-05-25-sendsms)。如使用控制台提供的测试签名/模板，接收号码须先在控制台绑定，具体可用资源以你的账号显示为准。

## 2. 配置本地环境变量

推荐在 IDE 的运行配置中设置环境变量，或在启动应用的同一个 PowerShell 窗口执行以下命令。替换占位内容；AccessKey 只保存在本地，不提交 Git，不发到聊天里。

```powershell
$env:ALIBABA_CLOUD_ACCESS_KEY_ID = 'your-ram-access-key-id'
$env:ALIBABA_CLOUD_ACCESS_KEY_SECRET = 'your-ram-access-key-secret'
$env:HMDP_SMS_ALIYUN_SIGN_NAME = '你的已审核签名'
$env:HMDP_SMS_ALIYUN_TEMPLATE_CODE = 'SMS_你的模板编号'
$env:HMDP_SMS_PROVIDER = 'aliyun'
```

这些变量只作用于当前 PowerShell 及其启动的进程。通过 IDE 启动时，应将它们填写到 IDE 的环境变量配置中。修改后重启应用。

也可使用模块下已被 Git 忽略的 `.env`，字段见 `.env.example`。该文件通过 Java properties 格式导入；中文签名建议使用上述环境变量，写入 `.env` 时需使用 `\uXXXX` 转义，避免中文编码问题。例如“城享汇”的 properties 写法为 `HMDP_SMS_ALIYUN_SIGN_NAME=\u57ce\u4eab\u6c47`。

选择 `aliyun` 后，缺少 AccessKey、签名或模板会导致启动失败，并提示缺少的配置项；不会自动回退成模拟成功。错误的 provider 名称也会导致启动失败。SDK 使用 HTTPS，连接超时默认 3 秒，读取超时默认 5 秒；发送不自动重试，避免超时后重复发送和扣费。

SDK 接入依据：[官方 Java SDK 示例](https://help.aliyun.com/zh/sms/developer-reference/using-java-openapi-example)。项目固定使用官方 `com.aliyun:dysmsapi20170525:4.6.0`，兼容本项目 Java 8 目标。

## 3. 启动和手机收信验证

确保项目的 MySQL、Redis、RabbitMQ 配置可用。在 `hm-dianping` Maven 模块目录执行：

```powershell
mvn -DskipTests package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

在另一个终端发送一次请求，将下面的占位内容换成你自己的手机号：

```powershell
curl.exe -X POST 'http://localhost:8081/user/code?phone=你的手机号'
```

接口返回 `{"success":true}` 代表阿里云返回 `Code=OK`，即**请求已受理**，不等于运营商已经投递到手机。确认手机收到短信，再用收到的验证码调用：

```http
POST /user/login
Content-Type: application/json

{"phone":"你的手机号","code":"手机收到的6位验证码"}
```

发送失败返回 `success=false` 和通用错误提示。本次写入的验证码通过 Lua 比较后删除，避免迟到的失败清理覆盖更新的验证码。日志仅保留服务商错误码、RequestId 或异常类型，不打印手机号、验证码、AccessKey 或 SDK 异常原文。用 RequestId 在控制台排查权限、签名、模板、余额或发送状态。

网络超时可能发生在阿里云已受理之后，因此手机仍可能收到短信；此时本次验证码已尝试作废，请等待发送间隔后重新申请。若 Redis 同时故障导致清理失败，验证码仍会在原 2 分钟 TTL 到期时失效。

## 4. 发送限制和开发模式

| 配置项 | 默认值 | 行为 |
| --- | --- | --- |
| `HMDP_SMS_PROVIDER` | `mock` | `mock` 本地模拟；`aliyun` 真实发送 |
| `HMDP_SMS_SEND_INTERVAL_SECONDS` | `60` | 同一手机号至少间隔 60 秒 |
| `HMDP_SMS_MAX_SENDS_PER_DAY` | `10` | 同一手机号自第一笔申请起的 24 小时窗口最多 10 次；并非自然日重置 |
| `HMDP_SMS_IP_MAX_COUNT` | `10` | 同一 IP 每 60 秒最多 10 次请求，复用现有注解式限流 |
| `HMDP_SMS_ALIYUN_CODE_PARAM_NAME` | `code` | 验证码模板变量名 |
| `HMDP_SMS_CONNECT_TIMEOUT_MS` | `3000` | SDK 连接超时毫秒数 |
| `HMDP_SMS_READ_TIMEOUT_MS` | `5000` | SDK 读取超时毫秒数 |

手机号间隔和 24 小时配额由同一段 Redis Lua 原子预占，多实例共享；发送失败也占用配额及间隔，避免对异常发送无限重试。Redis 不可用时拒绝发送。手机号限流始终执行；IP 限流受现有 `hmdp.rate-limit.enabled` 总开关控制，取 `request.getRemoteAddr()`，反向代理后的实际 IP 识别需按部署方式配置。

`mock` 模式也执行手机号限流，验证码只保存在开发 Redis，不会发短信、不写入响应或日志。测试应使用专用手机号及 Redis；开发时读取对应的 `login:code:<phone>` 验证码完成登录。自动化用例显式保持 `HMDP_SMS_PROVIDER=mock`，不要使用真实短信批量跑测试。

本次接入保持原有登录校验行为：登录成功后验证码仍保留至 TTL 到期。验证码一次性消费、校验失败次数限制等登录侧改造不包含在本次短信发送接入中。

## 5. 无短信费用的自动化验证

```powershell
mvn '-Dtest=SmsCodeServiceTest,AliyunSmsCodeSenderTest,SmsConfigurationTest' test
```

这些测试使用模拟 Redis/SDK，覆盖请求模板参数、前导零、超时及业务错误、发送失败清理、限流拒绝、缺失配置和模式选择；不访问真实短信服务。真实手机收信必须在账号资源就绪后按第 3 节单独验收。

另有 4 项可选的真实 Redis 集成测试。启动仅用于测试的本地 Redis 后，将其映射端口传入下面命令；不要使用业务 Redis。测试不会加载应用的数据库或短信配置，短信客户端仍然为模拟对象：

```powershell
mvn '-Dtest=SmsRedisIntegrationTest' '-Dsms.test.redis.port=你的测试Redis端口' test
```

2026-10-29 本地验证：16 项短信单元测试、4 项临时 Redis 7.2 容器集成测试、15 项相关缓存/消息/ID 回归测试，共 35 项通过，无失败或跳过。并发测试包含同一手机号 12 个请求仅触发一次发送。验证完成后已移除临时容器；未调用真实短信服务，尚未进行手机收信验证。
