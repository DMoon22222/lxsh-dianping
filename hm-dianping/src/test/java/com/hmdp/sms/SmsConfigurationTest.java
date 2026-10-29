package com.hmdp.sms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SmsConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SmsConfiguration.class);

    @Test
    void defaultsToMockWithoutCredentials() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SmsCodeSender.class)).isInstanceOf(MockSmsCodeSender.class);
        });
    }

    @Test
    void aliyunWithoutCredentialsFailsAtStartupRatherThanFallingBackToMock() {
        context.withPropertyValues("hmdp.sms.provider=aliyun").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "阿里云短信缺少 ALIBABA_CLOUD_ACCESS_KEY_ID");
        });
    }

    @Test
    void aliyunBuildsClientLocallyWithoutSendingAnySms() {
        context.withPropertyValues("hmdp.sms.provider=aliyun",
                "hmdp.sms.aliyun.access-key-id=test-id",
                "hmdp.sms.aliyun.access-key-secret=test-secret",
                "hmdp.sms.aliyun.sign-name=测试签名",
                "hmdp.sms.aliyun.template-code=SMS_TEST").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SmsCodeSender.class)).isInstanceOf(AliyunSmsCodeSender.class);
        });
    }

    @Test
    void unknownProviderFailsAtStartup() {
        context.withPropertyValues("hmdp.sms.provider=aliyn").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void personalModeSelectsPnvsClientWithoutOrdinarySmsResources() {
        pnvsContext().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SmsCodeSender.class)).isInstanceOf(AliyunPnvsSmsCodeSender.class);
        });
    }

    @Test
    void personalModeWithoutCredentialsFailsRatherThanUsingMock() {
        context.withPropertyValues("hmdp.sms.provider=aliyun-pnvs").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "阿里云短信认证缺少 ALIBABA_CLOUD_ACCESS_KEY_ID");
        });
    }

    @Test
    void personalModeRejectsMissingTemplateAndConflictingVariables() {
        pnvsContext().withPropertyValues("hmdp.sms.pnvs.template-code=")
                .run(ctx -> assertThat(ctx).hasFailed());
        pnvsContext().withPropertyValues("hmdp.sms.pnvs.minutes-param-name=code")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    private ApplicationContextRunner pnvsContext() {
        return context.withPropertyValues("hmdp.sms.provider=aliyun-pnvs",
                "hmdp.sms.pnvs.access-key-id=test-id",
                "hmdp.sms.pnvs.access-key-secret=test-secret",
                "hmdp.sms.pnvs.sign-name=测试赠送签名",
                "hmdp.sms.pnvs.template-code=100001");
    }

    @Test
    void invalidRateLimitFailsAtStartup() {
        context.withPropertyValues("hmdp.sms.send-interval-seconds=0")
                .run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("hmdp.sms.max-sends-per-day=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
