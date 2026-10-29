package com.hmdp.sms;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "hmdp.sms")
public class SmsProperties {
    public enum Provider { MOCK, ALIYUN, ALIYUN_PNVS }

    private Provider provider = Provider.MOCK;
    private long sendIntervalSeconds = 60;
    private int maxSendsPerDay = 10;
    private Aliyun aliyun = new Aliyun();
    private Pnvs pnvs = new Pnvs();

    // Do not generate toString(): this object contains credentials.
    @Getter
    @Setter
    public static class Aliyun {
        private String accessKeyId;
        private String accessKeySecret;
        private String signName;
        private String templateCode;
        private String codeParamName = "code";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;
    }

    @Getter
    @Setter
    public static class Pnvs extends Aliyun {
        private String minutesParamName = "min";
    }
}
