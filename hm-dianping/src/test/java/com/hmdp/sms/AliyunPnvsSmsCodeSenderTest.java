package com.hmdp.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AliyunPnvsSmsCodeSenderTest {
    private Client client;
    private SmsProperties.Pnvs properties;
    private AliyunPnvsSmsCodeSender sender;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        properties = new SmsProperties.Pnvs();
        properties.setSignName("测试赠送签名");
        properties.setTemplateCode("100001");
        sender = new AliyunPnvsSmsCodeSender(client, properties, 60);
    }

    @Test
    void sendsLiteralRedisCodeAndMatchingExpiryWithBothRetryMechanismsDisabled() throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(accepted());
        sender.sendCode("13800000000", "012345");

        ArgumentCaptor<SendSmsVerifyCodeRequest> request =
                ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
        ArgumentCaptor<RuntimeOptions> options = ArgumentCaptor.forClass(RuntimeOptions.class);
        verify(client).sendSmsVerifyCodeWithOptions(request.capture(), options.capture());
        SendSmsVerifyCodeRequest actual = request.getValue();
        assertEquals("13800000000", actual.getPhoneNumber());
        assertEquals("86", actual.getCountryCode());
        assertEquals("测试赠送签名", actual.getSignName());
        assertEquals("100001", actual.getTemplateCode());
        JsonNode template = new ObjectMapper().readTree(actual.getTemplateParam());
        assertEquals("012345", template.get("code").asText());
        assertEquals("2", template.get("min").asText());
        assertEquals(2, template.size());
        assertEquals(120L, actual.getValidTime());
        assertEquals(6L, actual.getCodeLength());
        assertEquals(60L, actual.getInterval());
        assertEquals(0L, actual.getAutoRetry());
        assertEquals(Boolean.FALSE, actual.getReturnVerifyCode());
        assertEquals(Boolean.FALSE, options.getValue().getAutoretry());
        assertEquals(3000, options.getValue().getConnectTimeout());
        assertEquals(5000, options.getValue().getReadTimeout());
    }

    @Test
    void configurableVariableNamesDoNotChangeTheCodeOrExpiry() throws Exception {
        properties.setCodeParamName("verifyCode");
        properties.setMinutesParamName("minutes");
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(accepted());
        sender.sendCode("13800000000", "012345");
        ArgumentCaptor<SendSmsVerifyCodeRequest> request =
                ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
        verify(client).sendSmsVerifyCodeWithOptions(request.capture(), any());
        JsonNode template = new ObjectMapper().readTree(request.getValue().getTemplateParam());
        assertEquals("012345", template.get("verifyCode").asText());
        assertEquals("2", template.get("minutes").asText());
    }

    @Test
    void requiresBothSuccessFlagAndOkCode() throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(
                new SendSmsVerifyCodeResponse().setBody(new SendSmsVerifyCodeResponseBody()
                        .setCode("OK").setSuccess(false)),
                new SendSmsVerifyCodeResponse().setBody(new SendSmsVerifyCodeResponseBody()
                        .setCode("BUSINESS_LIMIT_CONTROL").setSuccess(true)),
                new SendSmsVerifyCodeResponse().setBody(new SendSmsVerifyCodeResponseBody()
                        .setCode("OK")));
        for (int i = 0; i < 3; i++) {
            assertThrows(SmsSendException.class, () -> sender.sendCode("13800000000", "012345"));
        }
    }

    @Test
    void missingResponseFailsClosed() throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any()))
                .thenReturn(null, new SendSmsVerifyCodeResponse());
        assertThrows(SmsSendException.class, () -> sender.sendCode("13800000000", "012345"));
        assertThrows(SmsSendException.class, () -> sender.sendCode("13800000000", "012345"));
    }

    @Test
    void sdkFailureIsNotRetriedAndDetailsAreNotExposed() throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any()))
                .thenThrow(new Exception("private request and secret credential"));
        SmsSendException error = assertThrows(SmsSendException.class,
                () -> sender.sendCode("13800000000", "012345"));
        assertEquals("短信发送失败，请稍后重试", error.getMessage());
        assertNull(error.getCause());
        verify(client, times(1)).sendSmsVerifyCodeWithOptions(any(), any());
    }

    private SendSmsVerifyCodeResponse accepted() {
        return new SendSmsVerifyCodeResponse().setBody(
                new SendSmsVerifyCodeResponseBody().setCode("OK").setSuccess(true));
    }
}
