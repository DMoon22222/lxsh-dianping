package com.hmdp.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AliyunSmsCodeSenderTest {
    private Client client;
    private AliyunSmsCodeSender sender;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        SmsProperties.Aliyun properties = new SmsProperties.Aliyun();
        properties.setSignName("测试签名");
        properties.setTemplateCode("SMS_TEST");
        properties.setCodeParamName("verifyCode");
        sender = new AliyunSmsCodeSender(client, properties);
    }

    @Test
    void sendsConfiguredTemplateAndKeepsLeadingZeroWithNoAutomaticRetry() throws Exception {
        when(client.sendSmsWithOptions(any(), any())).thenReturn(
                new SendSmsResponse().setBody(new SendSmsResponseBody().setCode("OK")));

        sender.sendCode("13800000000", "012345");

        ArgumentCaptor<SendSmsRequest> request = ArgumentCaptor.forClass(SendSmsRequest.class);
        ArgumentCaptor<RuntimeOptions> options = ArgumentCaptor.forClass(RuntimeOptions.class);
        verify(client).sendSmsWithOptions(request.capture(), options.capture());
        assertEquals("13800000000", request.getValue().getPhoneNumbers());
        assertEquals("测试签名", request.getValue().getSignName());
        assertEquals("SMS_TEST", request.getValue().getTemplateCode());
        assertEquals("012345", new ObjectMapper().readTree(request.getValue().getTemplateParam())
                .get("verifyCode").asText());
        assertEquals(Boolean.FALSE, options.getValue().getAutoretry());
        assertEquals(3000, options.getValue().getConnectTimeout());
        assertEquals(5000, options.getValue().getReadTimeout());
    }

    @Test
    void businessRejectionDoesNotCountAsSuccess() throws Exception {
        when(client.sendSmsWithOptions(any(), any())).thenReturn(new SendSmsResponse()
                .setBody(new SendSmsResponseBody().setCode("isv.BUSINESS_LIMIT_CONTROL")));
        assertThrows(SmsSendException.class, () -> sender.sendCode("13800000000", "012345"));
    }

    @Test
    void missingResponseFailsClosed() throws Exception {
        when(client.sendSmsWithOptions(any(), any())).thenReturn(null, new SendSmsResponse());
        assertThrows(SmsSendException.class, () -> sender.sendCode("13800000000", "012345"));
        assertThrows(SmsSendException.class, () -> sender.sendCode("13800000000", "012345"));
    }

    @Test
    void exceptionDetailsAreNotExposedAndTimeoutDoesNotRetry() throws Exception {
        when(client.sendSmsWithOptions(any(), any()))
                .thenThrow(new Exception("secret credential and private request data"));
        SmsSendException error = assertThrows(SmsSendException.class,
                () -> sender.sendCode("13800000000", "012345"));
        assertEquals("短信发送失败，请稍后重试", error.getMessage());
        assertNull(error.getCause());
        verify(client, times(1)).sendSmsWithOptions(any(), any());
    }
}
