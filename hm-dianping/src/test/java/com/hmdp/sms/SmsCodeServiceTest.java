package com.hmdp.sms;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmsCodeServiceTest {
    private static final String PHONE = "13800000000";
    private static final String CODE_KEY = "login:code:" + PHONE;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SmsCodeSender sender;
    private SmsCodeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sender = mock(SmsCodeSender.class);
        service = new SmsCodeService(redis, sender, new SmsProperties());
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
    }

    @Test
    void invalidPhoneDoesNotTouchRedisOrProvider() {
        assertFalse(service.sendCode("123").getSuccess());
        verifyNoInteractions(sender);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
    }

    @Test
    void storesSameSixDigitCodeBeforeSendingAndReturnsNoCode() {
        Result result = service.sendCode(PHONE);

        assertTrue(result.getSuccess());
        assertNull(result.getData());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(CODE_KEY), code.capture(), eq(2L), eq(TimeUnit.MINUTES));
        assertTrue(code.getValue().matches("[0-9]{6}"));
        org.mockito.InOrder order = inOrder(values, sender);
        order.verify(values).set(CODE_KEY, code.getValue(), 2L, TimeUnit.MINUTES);
        order.verify(sender).sendCode(PHONE, code.getValue());
        verify(redis).execute(any(RedisScript.class), eq(Arrays.asList(
                "sms:send:{" + PHONE + "}:cooldown", "sms:send:{" + PHONE + "}:daily")),
                eq("60"), eq("10"));
    }

    @Test
    void cooldownOrDailyQuotaNeverSendsOrOverwritesExistingCode() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L, -1L, null);
        assertFalse(service.sendCode(PHONE).getSuccess());
        assertFalse(service.sendCode(PHONE).getSuccess());
        assertFalse(service.sendCode(PHONE).getSuccess());
        verifyNoInteractions(values, sender);
    }

    @Test
    void reserveStoreFailureNeverSends() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("test unavailable"));
        assertFalse(service.sendCode(PHONE).getSuccess());
        verifyNoInteractions(values, sender);
    }

    @Test
    void providerFailureRollsBackOnlyMatchingCodeAndRetainsRateLimits() {
        doThrow(new SmsSendException()).when(sender).sendCode(eq(PHONE), anyString());

        Result result = service.sendCode(PHONE);

        assertFalse(result.getSuccess());
        assertEquals("短信发送失败，请稍后重试", result.getErrorMsg());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender).sendCode(eq(PHONE), code.capture());
        verify(redis).execute(any(RedisScript.class), eq(Collections.singletonList(CODE_KEY)),
                eq(code.getValue()));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void codeStoreFailureNeverSendsAndAttemptsCleanup() {
        doThrow(new RedisConnectionFailureException("test write failure"))
                .when(values).set(eq(CODE_KEY), anyString(), anyLong(), eq(TimeUnit.MINUTES));
        assertFalse(service.sendCode(PHONE).getSuccess());
        verifyNoInteractions(sender);
        verify(redis).execute(any(RedisScript.class), eq(Collections.singletonList(CODE_KEY)),
                anyString());
    }

    @Test
    void cleanupFailureDoesNotMaskSmsFailure() {
        doThrow(new SmsSendException()).when(sender).sendCode(eq(PHONE), anyString());
        when(redis.execute(any(RedisScript.class), eq(Collections.singletonList(CODE_KEY)),
                anyString())).thenThrow(new RedisConnectionFailureException("test cleanup failure"));
        assertEquals("短信发送失败，请稍后重试", service.sendCode(PHONE).getErrorMsg());
    }
}
