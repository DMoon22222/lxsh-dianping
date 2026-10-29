package com.hmdp.service;

import com.hmdp.dto.ActivityMessageDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.DiscoveryMapper;
import com.hmdp.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActivityMessageServiceTest {
    private DiscoveryMapper mapper;
    private UserMapper users;
    private NearbyShopService nearby;
    private ZSetOperations<String, String> likes;
    private ActivityMessageService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final LocalDateTime since = LocalDateTime.now(clock).minusDays(30);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        mapper = mock(DiscoveryMapper.class);
        users = mock(UserMapper.class);
        nearby = mock(NearbyShopService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        likes = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(likes);
        service = new ActivityMessageService(mapper, users, redis, nearby);
        ReflectionTestUtils.setField(service, "clock", clock);
        when(mapper.ownedNotes(anyLong())).thenReturn(Collections.emptyList());
        when(mapper.recentNotes(anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(users.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());
    }

    @Test
    void newLikeOnAnOldOwnedNoteIsIncludedButSelfLikesAreNot() {
        Blog old = new Blog().setId(11L).setUserId(1L).setTitle("旧笔记").setCreateTime(since.minusYears(1));
        when(mapper.ownedNotes(1L)).thenReturn(Collections.singletonList(old));
        long recent = clock.millis() - 1000;
        when(likes.reverseRangeByScoreWithScores(eq("blog:liked:11"), anyDouble(), eq((double)clock.millis()), eq(0L), eq(50L)))
                .thenReturn(new LinkedHashSet<>(Arrays.asList(new DefaultTypedTuple<>("2", (double)recent),
                        new DefaultTypedTuple<>("1", (double)recent), new DefaultTypedTuple<>("invalid", (double)recent))));
        when(users.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(
                new User().setId(2L).setNickName("读者").setPhone("private-phone")));
        List<ActivityMessageDTO> messages = service.recent(1L, null, null);
        assertEquals(1, messages.size());
        assertEquals("读者 赞了你的笔记《旧笔记》", messages.get(0).getContent());
        assertEquals(11L, messages.get(0).getTargetId());
        assertEquals(recent, messages.get(0).getCreatedAt());
        verify(mapper).ownedNotes(1L);
        verify(mapper).recentNotes(1L, since, 50);
        verifyNoInteractions(nearby);
    }

    @Test
    void eachAccountGetsOnlyItsOwnLikesAndCancelledLikesDisappear() {
        when(mapper.ownedNotes(2L)).thenReturn(Collections.singletonList(new Blog().setId(22L).setUserId(2L)));
        when(likes.reverseRangeByScoreWithScores(eq("blog:liked:22"), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Collections.emptySet());
        assertTrue(service.recent(2L, null, null).isEmpty());
        verify(likes, never()).reverseRangeByScoreWithScores(eq("blog:liked:11"), anyDouble(), anyDouble(), anyLong(), anyLong());
    }

    @Test
    void mixesRealPublicationAndNearbyEventsInTimeOrder() {
        LocalDateTime now = LocalDateTime.now(clock);
        when(mapper.recentNotes(1L, since, 50)).thenReturn(Arrays.asList(
                new Blog().setId(12L).setUserId(1L).setTitle("我发布的").setCreateTime(now.minusHours(1)),
                new Blog().setId(13L).setUserId(3L).setTitle("关注的新文").setCreateTime(now.minusMinutes(10))));
        when(users.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(new User().setId(3L).setNickName("作者")));
        when(nearby.find(120.15, 30.33, 5, null, since, 0, 50)).thenReturn(Collections.singletonList(
                new Shop().setId(9L).setName("新店").setCreateTime(now.minusMinutes(1)).setDistance(123D)));
        List<ActivityMessageDTO> messages = service.recent(1L, 120.15, 30.33);
        assertEquals(Arrays.asList("NEW_SHOP", "FOLLOWED_POST", "PUBLISHED"), messages.stream()
                .map(ActivityMessageDTO::getType).collect(Collectors.toList()));
        assertEquals("SHOP", messages.get(0).getTargetType());
        assertEquals(123D, messages.get(0).getDistance());
        assertTrue(messages.get(1).getContent().contains("作者"));
    }

    @Test
    void limitsTheMergedFeedAndExcludesFutureEvents() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Blog> notes = new ArrayList<>();
        notes.add(new Blog().setId(999L).setUserId(1L).setCreateTime(now.plusDays(1)));
        for (long i = 1; i <= 60; i++) notes.add(new Blog().setId(i).setUserId(1L).setCreateTime(now.minusMinutes(i)));
        when(mapper.recentNotes(1L, since, 50)).thenReturn(notes);
        List<ActivityMessageDTO> messages = service.recent(1L, null, null);
        assertEquals(50, messages.size());
        assertEquals(1L, messages.get(0).getTargetId());
        assertEquals(50L, messages.get(49).getTargetId());
    }
}
