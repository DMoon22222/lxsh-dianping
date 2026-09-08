package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class loadUserTest {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadUserTokenToRedis() throws IOException {
        List<User> users = userService.list();

        String tokenFile = System.getProperty("hmdp.test-token-file", "tokens.txt");

        try (FileWriter writer = new FileWriter(Path.of(tokenFile).toFile())) {
            for (User user : users) {
                String token = UUID.randomUUID().toString(true);

                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

                Map<String, Object> userMap = BeanUtil.beanToMap(
                        userDTO,
                        new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
                );

                String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.DAYS);

                writer.write(token);
                writer.write("\n");
            }
        }
    }
}
