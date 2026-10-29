package com.hmdp.sms;

import com.hmdp.dto.Result;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;

@Slf4j
@Service
public class SmsCodeService {
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT =
            script("sms_reserve.lua");
    // Existing compare-and-delete script also prevents a late failure from deleting a newer code.
    private static final DefaultRedisScript<Long> DELETE_CODE_SCRIPT =
            script("unlock.lua");
    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;
    private final SmsCodeSender sender;
    private final SmsProperties properties;

    public SmsCodeService(StringRedisTemplate redis, SmsCodeSender sender, SmsProperties properties) {
        this.redis = redis;
        this.sender = sender;
        this.properties = properties;
    }

    private static DefaultRedisScript<Long> script(String resource) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(Long.class);
        return script;
    }

    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        String keyPrefix = "sms:send:{" + phone + "}:";
        Long reserved;
        try {
            reserved = redis.execute(RESERVE_SCRIPT,
                    Arrays.asList(keyPrefix + "cooldown", keyPrefix + "daily"),
                    String.valueOf(properties.getSendIntervalSeconds()),
                    String.valueOf(properties.getMaxSendsPerDay()));
        } catch (DataAccessException ex) {
            log.warn("短信限流存储不可用，type={}", ex.getClass().getSimpleName());
            return Result.fail("验证码服务暂不可用，请稍后重试");
        }
        if (reserved == null) {
            return Result.fail("验证码服务暂不可用，请稍后重试");
        }
        if (reserved == 0L) {
            return Result.fail("验证码发送过于频繁，请稍后重试");
        }
        if (reserved != 1L) {
            return Result.fail("验证码发送次数已达上限，请24小时后再试");
        }

        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String codeKey = LOGIN_CODE_KEY + phone;
        try {
            // Save before the external call: never send a code that we already know cannot be verified.
            redis.opsForValue().set(codeKey, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
            sender.sendCode(phone, code);
            return Result.ok();
        } catch (SmsSendException ex) {
            deleteFailedCode(codeKey, code);
            return Result.fail(ex.getMessage());
        } catch (DataAccessException ex) {
            log.warn("验证码写入失败，type={}", ex.getClass().getSimpleName());
            deleteFailedCode(codeKey, code);
            return Result.fail("验证码服务暂不可用，请稍后重试");
        }
    }

    private void deleteFailedCode(String key, String code) {
        try {
            redis.execute(DELETE_CODE_SCRIPT, Collections.singletonList(key), code);
        } catch (DataAccessException ex) {
            // The code still expires naturally after two minutes. Retain rate limits on all failures.
            log.warn("失败验证码清理未完成，type={}", ex.getClass().getSimpleName());
        }
    }
}
