package com.hmdp.sms;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in test against an isolated local Redis, never against application configuration. */
@EnabledIfSystemProperty(named = "sms.test.redis.port", matches = "\\d+")
class SmsRedisIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private SmsCodeSender sender;
    private SmsCodeService service;
    private String phone;
    private String codeKey;
    private String cooldownKey;
    private String dailyKey;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("sms.test.redis.port"));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        phone = "138" + ThreadLocalRandom.current().nextLong(10_000_000L, 99_999_999L);
        codeKey = "login:code:" + phone;
        cooldownKey = "sms:send:{" + phone + "}:cooldown";
        dailyKey = "sms:send:{" + phone + "}:daily";
        sender = mock(SmsCodeSender.class);
        service = new SmsCodeService(redis, sender, new SmsProperties());
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            // Only delete this test's exact keys. Never flush the database.
            redis.delete(Arrays.asList(codeKey, cooldownKey, dailyKey));
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @Timeout(20)
    void concurrentRequestsMakeOnlyOneProviderCallAndRetainCodeTtl() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> responses = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                responses.add(workers.submit(() -> {
                    start.await();
                    return service.sendCode(phone);
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Result> response : responses) {
                if (response.get(10, TimeUnit.SECONDS).getSuccess()) {
                    successes++;
                }
            }
            assertEquals(1, successes);
            verify(sender, times(1)).sendCode(eq(phone), anyString());
            assertTrue(redis.opsForValue().get(codeKey).matches("[0-9]{6}"));
            assertTrue(redis.getExpire(codeKey) > 0 && redis.getExpire(codeKey) <= 120);
            assertTrue(redis.getExpire(cooldownKey) > 0 && redis.getExpire(cooldownKey) <= 60);
            assertEquals("1", redis.opsForValue().get(dailyKey));
            assertTrue(redis.getExpire(dailyKey) > 86_000 && redis.getExpire(dailyKey) <= 86_400);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void quotaRejectionDoesNotOverwriteLastSuccessfulCode() {
        SmsProperties properties = new SmsProperties();
        properties.setMaxSendsPerDay(2);
        service = new SmsCodeService(redis, sender, properties);
        assertTrue(service.sendCode(phone).getSuccess());
        redis.delete(cooldownKey); // Advance past the interval without sleeping.
        assertTrue(service.sendCode(phone).getSuccess());
        String previous = redis.opsForValue().get(codeKey);
        redis.delete(cooldownKey);
        assertFalse(service.sendCode(phone).getSuccess());
        assertEquals(previous, redis.opsForValue().get(codeKey));
        assertEquals("2", redis.opsForValue().get(dailyKey));
        verify(sender, times(2)).sendCode(eq(phone), anyString());
    }

    @Test
    void failedSendDeletesCodeButKeepsCooldownAndQuota() {
        doThrow(new SmsSendException()).when(sender).sendCode(eq(phone), anyString());
        assertFalse(service.sendCode(phone).getSuccess());
        assertFalse(redis.hasKey(codeKey));
        assertTrue(redis.hasKey(cooldownKey));
        assertEquals("1", redis.opsForValue().get(dailyKey));
        assertFalse(service.sendCode(phone).getSuccess());
        verify(sender, times(1)).sendCode(eq(phone), anyString());
    }

    @Test
    void lateCleanupDoesNotDeleteReplacedCode() {
        DefaultRedisScript<Long> delete = new DefaultRedisScript<>();
        delete.setLocation(new ClassPathResource("unlock.lua"));
        delete.setResultType(Long.class);
        redis.opsForValue().set(codeKey, "new-code", 2, TimeUnit.MINUTES);
        assertEquals(0L, redis.execute(delete, Collections.singletonList(codeKey), "old-code"));
        assertEquals("new-code", redis.opsForValue().get(codeKey));
        assertEquals(1L, redis.execute(delete, Collections.singletonList(codeKey), "new-code"));
        assertFalse(redis.hasKey(codeKey));
    }
}
