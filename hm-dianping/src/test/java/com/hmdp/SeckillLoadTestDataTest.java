package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.PendingOrderService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IUserService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 创建可重复使用的秒杀压测数据。
 *
 * <p>此测试只有在明确传入 {@code -Dhmdp.load-test.prepare=true} 时才会运行。
 * 它会删除指定 voucherId 的订单、重置该券的数据库/Redis 库存，并创建或复用
 * {@code 1399xxxxxxxx} 号段的测试用户。请仅针对专用压测数据库执行。</p>
 */
@SpringBootTest(properties = {
        "hmdp.instance-id=load-test-prep",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class SeckillLoadTestDataTest {

    private static final String TEST_PHONE_PREFIX = "1399";

    @Resource
    private IUserService userService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @EnabledIfSystemProperty(named = "hmdp.load-test.prepare", matches = "true")
    void prepare() throws IOException {
        long voucherId = requiredLong("hmdp.load-test.voucher-id");
        int userCount = optionalPositiveInt("hmdp.load-test.user-count", 1000);
        int stock = optionalPositiveInt("hmdp.load-test.stock", userCount);
        Path tokenFile = Paths.get(System.getProperty(
                "hmdp.load-test.token-file",
                "target/jmeter/tokens.csv"
        ));

        resetVoucher(voucherId, stock);
        List<User> users = loadOrCreateTestUsers(userCount);
        writeTokens(users, tokenFile);
    }

    private void resetVoucher(long voucherId, int stock) {
        boolean exists = seckillVoucherService.count(new QueryWrapper<SeckillVoucher>()
                .eq("voucher_id", voucherId))
                > 0;
        if (!exists) {
            throw new IllegalArgumentException("秒杀券不存在，voucherId=" + voucherId);
        }

        voucherOrderService.remove(new QueryWrapper<VoucherOrder>()
                .eq("voucher_id", voucherId));
        seckillVoucherService.update()
                .set("stock", stock)
                .eq("voucher_id", voucherId)
                .update();

        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String orderKey = "seckill:order:" + voucherId;
        stringRedisTemplate.delete(stockKey);
        stringRedisTemplate.delete(orderKey);
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock));
        clearPendingDataForVoucher(voucherId);
    }

    private void clearPendingDataForVoucher(long voucherId) {
        java.util.Set<String> dataKeys = stringRedisTemplate.keys(
                PendingOrderService.DATA_KEY_PREFIX + "*"
        );
        if (dataKeys == null) {
            return;
        }

        for (String dataKey : dataKeys) {
            Object storedVoucherId = stringRedisTemplate.opsForHash().get(dataKey, "voucherId");
            if (!String.valueOf(voucherId).equals(String.valueOf(storedVoucherId))) {
                continue;
            }
            String orderId = dataKey.substring(PendingOrderService.DATA_KEY_PREFIX.length());
            stringRedisTemplate.opsForZSet().remove(PendingOrderService.PENDING_KEY, orderId);
            stringRedisTemplate.opsForZSet().remove(PendingOrderService.FAILED_KEY, orderId);
            stringRedisTemplate.delete(dataKey);
        }
    }

    private List<User> loadOrCreateTestUsers(int userCount) {
        Map<String, User> existingUsers = new HashMap<>();
        List<User> existing = userService.list(new QueryWrapper<User>()
                .likeRight("phone", TEST_PHONE_PREFIX));
        for (User user : existing) {
            existingUsers.put(user.getPhone(), user);
        }

        for (int i = 0; i < userCount; i++) {
            String phone = String.format("%s%07d", TEST_PHONE_PREFIX, i);
            if (!existingUsers.containsKey(phone)) {
                User user = new User();
                user.setPhone(phone);
                user.setNickName("load-test-" + i);
                user.setIcon("");
                userService.save(user);
                existingUsers.put(phone, user);
            }
        }

        return existingUsers.values().stream()
                .sorted((left, right) -> left.getPhone().compareTo(right.getPhone()))
                .limit(userCount)
                .collect(java.util.stream.Collectors.toList());
    }

    private void writeTokens(List<User> users, Path tokenFile) throws IOException {
        Path parent = tokenFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(tokenFile, StandardCharsets.UTF_8)) {
            for (User user : users) {
                String token = UUID.randomUUID().toString(true);
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                Map<String, Object> userMap = BeanUtil.beanToMap(
                        userDTO,
                        new HashMap<String, Object>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, value) -> String.valueOf(value))
                );

                stringRedisTemplate.opsForHash().putAll(
                        RedisConstants.LOGIN_USER_KEY + token,
                        userMap
                );
                stringRedisTemplate.expire(
                        RedisConstants.LOGIN_USER_KEY + token,
                        RedisConstants.LOGIN_USER_TTL,
                        TimeUnit.DAYS
                );
                writer.write(token);
                writer.newLine();
            }
        }
    }

    private long requiredLong(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少系统参数 -D" + property + "=<value>");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数必须为整数：" + property, e);
        }
    }

    private int optionalPositiveInt(String property, int defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("参数必须大于 0：" + property);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数必须为整数：" + property, e);
        }
    }
}
