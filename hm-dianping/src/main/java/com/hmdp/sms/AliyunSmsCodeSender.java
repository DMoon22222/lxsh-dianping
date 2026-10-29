package com.hmdp.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;

@Slf4j
public class AliyunSmsCodeSender implements SmsCodeSender {
    private final Client client;
    private final SmsProperties.Aliyun properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AliyunSmsCodeSender(Client client, SmsProperties.Aliyun properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void sendCode(String phone, String code) {
        SendSmsResponse response;
        try {
            String templateParam = objectMapper.writeValueAsString(
                    Collections.singletonMap(properties.getCodeParamName(), code));
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(properties.getSignName())
                    .setTemplateCode(properties.getTemplateCode())
                    .setTemplateParam(templateParam);
            RuntimeOptions runtime = new RuntimeOptions()
                    .setConnectTimeout(properties.getConnectTimeoutMs())
                    .setReadTimeout(properties.getReadTimeoutMs())
                    // A timeout does not prove non-delivery. Never retry a paid send automatically.
                    .setAutoretry(false);
            response = client.sendSmsWithOptions(request, runtime);
        } catch (Exception ex) {
            log.warn("阿里云短信请求异常，type={}", ex.getClass().getSimpleName());
            throw new SmsSendException();
        }

        SendSmsResponseBody body = response == null ? null : response.getBody();
        if (body == null || !"OK".equals(body.getCode())) {
            log.warn("阿里云短信请求未受理，code={}, requestId={}",
                    body == null ? "EMPTY_RESPONSE" : body.getCode(),
                    body == null ? "" : body.getRequestId());
            throw new SmsSendException();
        }
        log.debug("阿里云短信请求已受理，requestId={}", body.getRequestId());
    }
}
