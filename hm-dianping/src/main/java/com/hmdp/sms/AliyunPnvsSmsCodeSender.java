package com.hmdp.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;

@Slf4j
public class AliyunPnvsSmsCodeSender implements SmsCodeSender {
    private final Client client;
    private final SmsProperties.Pnvs properties;
    private final long intervalSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AliyunPnvsSmsCodeSender(Client client, SmsProperties.Pnvs properties, long intervalSeconds) {
        this.client = client;
        this.properties = properties;
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void sendCode(String phone, String code) {
        SendSmsVerifyCodeResponse response;
        try {
            Map<String, String> template = new LinkedHashMap<>();
            // A literal code preserves the existing Redis verification flow. Do not use ##code##.
            template.put(properties.getCodeParamName(), code);
            template.put(properties.getMinutesParamName(), String.valueOf(LOGIN_CODE_TTL));
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                    .setCountryCode("86")
                    .setPhoneNumber(phone)
                    .setSignName(properties.getSignName())
                    .setTemplateCode(properties.getTemplateCode())
                    .setTemplateParam(objectMapper.writeValueAsString(template))
                    .setCodeLength(6L)
                    .setCodeType(1L)
                    .setValidTime(TimeUnit.MINUTES.toSeconds(LOGIN_CODE_TTL))
                    .setInterval(intervalSeconds)
                    .setReturnVerifyCode(false)
                    // Disable both provider-side signature retries and SDK transport retries.
                    .setAutoRetry(0L);
            RuntimeOptions runtime = new RuntimeOptions()
                    .setConnectTimeout(properties.getConnectTimeoutMs())
                    .setReadTimeout(properties.getReadTimeoutMs())
                    .setAutoretry(false);
            response = client.sendSmsVerifyCodeWithOptions(request, runtime);
        } catch (Exception ex) {
            log.warn("阿里云短信认证请求异常，type={}", ex.getClass().getSimpleName());
            throw new SmsSendException();
        }

        SendSmsVerifyCodeResponseBody body = response == null ? null : response.getBody();
        if (body == null || !Boolean.TRUE.equals(body.getSuccess()) || !"OK".equals(body.getCode())) {
            log.warn("阿里云短信认证请求未受理，code={}, requestId={}",
                    body == null ? "EMPTY_RESPONSE" : body.getCode(),
                    body == null ? "" : body.getRequestId());
            throw new SmsSendException();
        }
        log.debug("阿里云短信认证请求已受理，requestId={}", body.getRequestId());
    }
}
