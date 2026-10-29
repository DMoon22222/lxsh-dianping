package com.hmdp.controller;

import com.hmdp.config.MvcConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BlogReadAccessTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void anonymousNoteAndLikeListAreReadable() throws Exception {
        mvc.perform(get("/blog/1")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/blog/likes/1")).andExpect(status().isOk());
    }

    @Test
    void readingDoesNotMakeWritingOrPrivateListsPublic() throws Exception {
        mvc.perform(post("/blog").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/blog/like/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/blog/of/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/me")).andExpect(status().isUnauthorized());
    }

    @Configuration
    @EnableWebMvc
    @Import({MvcConfig.class, BlogController.class, UserController.class,
            LoginInterceptor.class, RefreshTokenInterceptor.class})
    static class TestConfig {
        @Bean IBlogService blogs() {
            IBlogService service = mock(IBlogService.class);
            when(service.queryById(1L)).thenReturn(Result.ok(new Blog().setId(1L).setContent("正文")));
            when(service.queryBlogLikes(1L)).thenReturn(Result.ok(Collections.emptyList()));
            return service;
        }
        @Bean IUserService users() { return mock(IUserService.class); }
        @Bean IUserInfoService info() { return mock(IUserInfoService.class); }
        @Bean IFollowService follows() { return mock(IFollowService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
    }
}
