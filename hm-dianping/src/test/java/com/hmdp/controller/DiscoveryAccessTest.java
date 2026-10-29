package com.hmdp.controller;

import com.hmdp.config.MvcConfig;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.ActivityMessageService;
import com.hmdp.service.NearbyShopService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DiscoveryAccessTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        UserHolder.removeUser();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void cleanup() { UserHolder.removeUser(); context.close(); }

    @Test
    void nearbyIsPublicButMessagesArePrivate() throws Exception {
        mvc.perform(get("/shop/nearby").param("x", "120.15").param("y", "30.33"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/messages")).andExpect(status().isUnauthorized());
        verifyNoInteractions(context.getBean(ActivityMessageService.class));
    }

    @Test
    void messageIdentityComesFromTheSessionNotAQueryParameter() throws Exception {
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        mvc.perform(get("/messages").param("userId", "999").param("x", "120.15").param("y", "30.33"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(context.getBean(ActivityMessageService.class)).recent(7L, 120.15, 30.33);
    }

    @Test
    void invalidCoordinatesRangesAndPagesCannotReachTheDatabase() throws Exception {
        for (String[] args : new String[][]{{"NaN", "30", "5", "1"}, {"181", "30", "5", "1"},
                {"120", "91", "5", "1"}, {"120", "30", "Infinity", "1"},
                {"120", "30", "21", "1"}, {"120", "30", "5", "0"}}) {
            mvc.perform(get("/shop/nearby").param("x", args[0]).param("y", args[1])
                    .param("radiusKm", args[2]).param("current", args[3]))
                    .andExpect(jsonPath("$.success").value(false));
        }
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        mvc.perform(get("/messages").param("x", "120")).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(context.getBean(NearbyShopService.class), context.getBean(ActivityMessageService.class));
    }

    @Configuration
    @EnableWebMvc
    @Import({MvcConfig.class, NearbyShopController.class, ActivityMessageController.class,
            LoginInterceptor.class, RefreshTokenInterceptor.class})
    static class Config {
        @Bean NearbyShopService nearby() { return mock(NearbyShopService.class); }
        @Bean ActivityMessageService messages() { return mock(ActivityMessageService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
    }
}
