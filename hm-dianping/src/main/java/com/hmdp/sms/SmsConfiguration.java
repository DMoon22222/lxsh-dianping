package com.hmdp.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Slf4j
@Configuration
@EnableConfigurationProperties(SmsProperties.class)
public class SmsConfiguration {
    @Bean
    public SmsCodeSender smsCodeSender(SmsProperties properties) throws Exception {
        Assert.notNull(properties.getProvider(), "hmdp.sms.provider 不能为空");
        Assert.isTrue(properties.getSendIntervalSeconds() > 0,
                "hmdp.sms.send-interval-seconds 必须大于0");
        Assert.isTrue(properties.getMaxSendsPerDay() > 0,
                "hmdp.sms.max-sends-per-day 必须大于0");
        if (properties.getProvider() == SmsProperties.Provider.MOCK) {
            log.warn("短信为 MOCK 模式，不会向手机发送短信；个人短信认证请选择 HMDP_SMS_PROVIDER=aliyun-pnvs");
            return new MockSmsCodeSender();
        }

        boolean pnvs = properties.getProvider() == SmsProperties.Provider.ALIYUN_PNVS;
        SmsProperties.Aliyun aliyun = pnvs ? properties.getPnvs() : properties.getAliyun();
        String label = pnvs ? "阿里云短信认证" : "阿里云短信";
        String configPrefix = pnvs ? "HMDP_SMS_PNVS_" : "HMDP_SMS_ALIYUN_";
        Assert.notNull(aliyun, pnvs ? "hmdp.sms.pnvs 不能为空" : "hmdp.sms.aliyun 不能为空");
        Assert.hasText(aliyun.getAccessKeyId(), label + "缺少 ALIBABA_CLOUD_ACCESS_KEY_ID");
        Assert.hasText(aliyun.getAccessKeySecret(), label + "缺少 ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        Assert.hasText(aliyun.getSignName(), label + "缺少 " + configPrefix + "SIGN_NAME");
        Assert.hasText(aliyun.getTemplateCode(), label + "缺少 " + configPrefix + "TEMPLATE_CODE");
        Assert.hasText(aliyun.getCodeParamName(), "阿里云短信模板变量名不能为空");
        Assert.isTrue(aliyun.getConnectTimeoutMs() > 0 && aliyun.getReadTimeoutMs() > 0,
                "阿里云短信连接及读取超时必须大于0");
        if (pnvs) {
            Assert.hasText(properties.getPnvs().getMinutesParamName(), "短信认证有效期模板变量名不能为空");
            Assert.isTrue(!properties.getPnvs().getMinutesParamName().equals(aliyun.getCodeParamName()),
                    "短信认证验证码和有效期的模板变量名不能相同");
        }

        Config config = new Config()
                .setAccessKeyId(aliyun.getAccessKeyId())
                .setAccessKeySecret(aliyun.getAccessKeySecret())
                .setRegionId("cn-hangzhou")
                .setProtocol("HTTPS")
                .setEndpoint(pnvs ? "dypnsapi.aliyuncs.com" : "dysmsapi.aliyuncs.com");
        if (pnvs) {
            return new AliyunPnvsSmsCodeSender(new com.aliyun.dypnsapi20170525.Client(config),
                    properties.getPnvs(), properties.getSendIntervalSeconds());
        }
        return new AliyunSmsCodeSender(new Client(config), aliyun);
    }
}
