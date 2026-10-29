# 个人短信认证：账号测试完成后的本地接入步骤

适用于已在阿里云完成个人实名认证、短信认证/融合认证开通、RAM 用户授权，并已通过控制台收信测试的个人学习项目。

项目现在支持 `HMDP_SMS_PROVIDER=aliyun-pnvs`，调用号码认证服务的 `SendSmsVerifyCode`。`mock` 继续作为开发测试默认模式；原来的 `aliyun` 模式对应普通短信服务，保留给已有企业短信资源的场景。

## 1. 准备四项本地配置

在你自己的电脑上准备以下值，无需发到聊天里：

| 项目 | 从哪里获取 |
| --- | --- |
| AccessKey ID | 刚创建的项目专用 RAM 用户 |
| AccessKey Secret | 创建 AccessKey 时保存的结果文件 |
| 赠送签名名称 | 号码认证控制台的赠送签名配置 |
| 赠送模板编号 | 号码认证控制台的赠送模板配置；选择登录/注册场景 |

签名和模板使用同一产品中配套的系统赠送资源，不使用普通短信服务的 `SMS_...` 模板。实际内容以你的控制台为准，不直接复制文档示例中的签名或模板编号。[官方接口参数](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-sendsmsverifycode)

## 2. 在 IDE 配置环境变量

如果用 IntelliJ IDEA，在“运行 → 编辑配置”中选择启动 `HmDianPingApplication` 的配置，在“环境变量”中分别添加：

```properties
HMDP_SMS_PROVIDER=aliyun-pnvs
ALIBABA_CLOUD_ACCESS_KEY_ID=你自己的AccessKeyID
ALIBABA_CLOUD_ACCESS_KEY_SECRET=你自己的AccessKeySecret
HMDP_SMS_PNVS_SIGN_NAME=控制台显示的赠送签名名称
HMDP_SMS_PNVS_TEMPLATE_CODE=控制台显示的赠送模板编号
```

这是环境变量格式，不是需要提交的源码。只在本地把占位内容替换成真实值。IDE 中直接填写中文签名即可。

赠送模板默认变量为 `${code}` 和 `${min}`，项目会传入本次 6 位验证码和字符串 `2`。如实际模板变量不同，可额外设置 `HMDP_SMS_PNVS_CODE_PARAM_NAME` 和 `HMDP_SMS_PNVS_MINUTES_PARAM_NAME`，默认分别为 `code`、`min`。

也可以把这些键写入模块目录的 `.env`。该文件使用 Java properties 方式加载，中文签名需要转为 `\uXXXX`，所以优先使用 IDE 环境变量。项目已忽略 `.env`，不要把真实 AccessKey 放入 `.env.example`、源码、终端报告或截图。

## 3. 重启后端

确保原项目的 MySQL、Redis、RabbitMQ 可用，然后使用刚设置的 IDE 运行配置重新启动后端。新模式缺少 AccessKey、签名或模板时会在启动阶段指出缺少哪个配置项。

如果使用命令行，请在同一 PowerShell 窗口设置环境变量后运行已打包的应用：

```powershell
Set-Location 'F:\hmdpcode\hm-dianping\hm-dianping'
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

注意：IDE 的环境变量仅作用于 IDE 启动的应用，不会自动传给另一个 PowerShell 窗口。

## 4. 从项目申请一次验证码

可以直接在项目登录页输入自己的手机号并点击获取验证码。也可以在另一个终端调用后端；默认端口为 `8081`：

```powershell
$smsTestPhone = Read-Host '请输入你的手机号'
curl.exe -X POST "http://localhost:8081/user/code?phone=$smsTestPhone"
```

`success=true` 代表服务商受理了请求。确认手机收到 6 位验证码，再在项目登录页输入并登录。验证码 2 分钟内有效，同一手机号至少间隔 60 秒发送一次，默认自第一笔申请起的 24 小时窗口最多 10 次。这个项目测试会产生真实短信费用，不要将真实模式用于批量自动化测试。

如果通过接口完成登录，在接口工具中使用：

```http
POST http://localhost:8081/user/login
Content-Type: application/json

{"phone":"你自己的手机号","code":"刚收到的验证码"}
```

登录返回的 `data` 是 Token。随后调用 `GET /user/me`，请求头 `authorization` 填原始 Token，即可核对登录身份。

## 5. 常见结果

| 现象 | 检查项 |
| --- | --- |
| 启动提示仍为 MOCK 模式 | 环境变量是否配置在实际启动的进程中，以及修改后是否已重启 |
| 启动提示缺少配置 | 根据提示填写 `ALIBABA_CLOUD_*`、`HMDP_SMS_PNVS_SIGN_NAME` 或 `HMDP_SMS_PNVS_TEMPLATE_CODE` |
| 手机号格式不正确 | 检查号码是否为 11 位、无空格、未附加 `+86`；项目保留了原有手机号格式校验 |
| 验证码发送过于频繁 | 等待发送间隔，避免连续点击；控制台刚测试过也可能触发平台限流 |
| 短信发送失败 | 用日志中的服务商错误码、RequestId 排查；客户端只返回通用提示，不暴露服务商请求原文 |
| 权限相关错误 | 发送使用 `dypns:SendSmsVerifyCode`；确认授权给了当前 AccessKey 所属的 RAM 用户 |
| `FUNCTION_NOT_OPENED` | 核对是否已开通官方流程要求的融合认证功能 |
| `INVALID_PARAMETERS` | 核对赠送签名/模板搭配及模板变量名 |
| 发送成功但未收信 | 查询控制台发送结果；接口受理不等于手机已收到 |

服务商错误码依据：[SendSmsVerifyCode 文档](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-sendsmsverifycode)。控制台的快速测试仅面向已绑定测试号码；项目通过正式短信认证 API 指定目标手机号，仍受平台发送限制及账号状态约束。

## 实现与验证范围

本次适配采用 API 支持的“传入具体验证码”方式：验证码由项目生成和保存到 Redis，再作为模板变量发送。Redis TTL 和短信文案均为 2 分钟，原有 `/user/code`、`/user/login` 和登录校验行为保持一致。由于传入的是本地生成的验证码，当前登录不调用 `CheckSmsVerifyCode`；阿里云核验接口适用于由平台动态生成的验证码。[官方参数说明](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-sendsmsverifycode)

请求显式关闭 SDK 重试、平台换签名重试和响应返回验证码。发送失败会沿用已有的验证码清理及限流处理，不打印手机号、验证码或 AccessKey。登录成功后仍保留验证码至 TTL 到期，登录侧一次性消费等行为未在本次适配中改动。

本地无短信费用的单元及相关回归测试命令：

```powershell
mvn '-Dtest=SmsCodeServiceTest,Aliyun*SmsCodeSenderTest,SmsConfigurationTest,ShopServiceImplTest,ShopCacheServiceTest,SnowflakeIdGeneratorTest,CacheInvalidatePublisherTest,CacheInvalidationOutboxTaskTest,PendingOrderRepublishTaskTest,SeckillRedisKeysTest' test
```

测试使用模拟短信客户端，不加载真实 AccessKey，不发送短信。真实项目收信和登录需由你按第 2～4 步完成。

2026-10-29 本地验证：24 项短信相关单元测试和 15 项缓存/消息/ID 回归测试全部通过，共 39 项，无失败或跳过；更新后的应用 JAR 已成功打包。未读取真实 AccessKey，未代替用户调用真实短信接口。
