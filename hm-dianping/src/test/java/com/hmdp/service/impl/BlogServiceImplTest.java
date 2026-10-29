package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogServiceImplTest {
    private BlogServiceImpl service;
    private IUserService users;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        service = spy(new BlogServiceImpl());
        users = mock(IUserService.class);
        redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        doReturn(new Blog().setId(1L).setUserId(2L).setContent("完整笔记内容"))
                .when(service).getById(1L);
        when(users.getById(2L)).thenReturn(new User().setId(2L).setNickName("作者"));
    }

    @AfterEach
    void cleanUser() {
        UserHolder.removeUser();
    }

    @Test
    void guestCanReadNoteWithoutLookingUpLikeState() {
        Result result = service.queryById(1L);
        assertTrue(result.getSuccess());
        Blog note = (Blog) result.getData();
        assertEquals("完整笔记内容", note.getContent());
        assertFalse(note.getIsLike());
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loggedInReaderGetsPersonalLikeState() {
        UserDTO reader = new UserDTO();
        reader.setId(3L);
        UserHolder.saveUser(reader);
        ZSetOperations<String, String> likes = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(likes);
        when(likes.score("blog:liked:1", "3")).thenReturn(10D);
        assertTrue(((Blog) service.queryById(1L).getData()).getIsLike());
    }

    @Test
    void missingAuthorDoesNotHideExistingNote() {
        when(users.getById(2L)).thenReturn(null);
        Blog note = (Blog) service.queryById(1L).getData();
        assertEquals("用户已注销", note.getName());
        assertEquals("完整笔记内容", note.getContent());
    }
}
