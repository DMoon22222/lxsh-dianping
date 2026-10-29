package com.hmdp.service;

import com.hmdp.dto.ActivityMessageDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.DiscoveryMapper;
import com.hmdp.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** A recent-activity view derived from real records, rather than a synthetic inbox. */
@Service
public class ActivityMessageService {
    private static final int LIMIT = 50;
    private final DiscoveryMapper mapper;
    private final UserMapper users;
    private final StringRedisTemplate redis;
    private final NearbyShopService nearby;
    private Clock clock = Clock.systemDefaultZone();

    public ActivityMessageService(DiscoveryMapper mapper, UserMapper users,
                                   StringRedisTemplate redis, NearbyShopService nearby) {
        this.mapper = mapper;
        this.users = users;
        this.redis = redis;
        this.nearby = nearby;
    }

    public List<ActivityMessageDTO> recent(Long userId, Double x, Double y) {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(30);
        long minTime = since.atZone(clock.getZone()).toInstant().toEpochMilli();
        long now = clock.millis();
        List<ActivityMessageDTO> messages = new ArrayList<>();
        Map<Long, String> actorNames = new HashMap<>();
        Map<String, Long> likeActors = new HashMap<>();
        List<Blog> owned = mapper.ownedNotes(userId);

        // Only inspect notes owned by this authenticated user. A userId query parameter is never accepted.
        for (Blog blog : owned) {
            Set<ZSetOperations.TypedTuple<String>> likes = redis.opsForZSet()
                    .reverseRangeByScoreWithScores("blog:liked:" + blog.getId(), minTime, now, 0, LIMIT);
            if (likes == null) continue;
            for (ZSetOperations.TypedTuple<String> like : likes) {
                if (like.getValue() == null || like.getScore() == null) continue;
                Long actor;
                try { actor = Long.valueOf(like.getValue()); }
                catch (NumberFormatException ignored) { continue; }
                if (actor.equals(userId)) continue;
                actorNames.put(actor, "用户已注销");
                String id = "like:" + blog.getId() + ":" + actor;
                likeActors.put(id, actor);
                messages.add(new ActivityMessageDTO(id,
                        "LIKE", "收到新的点赞", null, blog.getImages(), "BLOG", blog.getId(),
                        like.getScore().longValue(), null));
            }
        }

        List<Blog> notes = mapper.recentNotes(userId, since, LIMIT);
        for (Blog blog : notes) {
            if (!userId.equals(blog.getUserId())) actorNames.put(blog.getUserId(), "用户已注销");
        }
        if (!actorNames.isEmpty()) {
            // Return only nicknames, never phone numbers or other account fields.
            for (User user : users.selectBatchIds(actorNames.keySet())) {
                actorNames.put(user.getId(), user.getNickName() == null ? "用户" : user.getNickName());
            }
        }
        Map<Long, String> titles = owned.stream()
                .collect(Collectors.toMap(Blog::getId, b -> b.getTitle() == null ? "未命名笔记" : b.getTitle()));
        for (ActivityMessageDTO message : messages) {
            Long actor = likeActors.get(message.getId());
            message.setContent(actorNames.get(actor) + " 赞了你的笔记《" + titles.get(message.getTargetId()) + "》");
        }
        for (Blog blog : notes) {
            if (blog.getCreateTime() == null) continue;
            boolean mine = userId.equals(blog.getUserId());
            messages.add(new ActivityMessageDTO("note:" + blog.getId(), mine ? "PUBLISHED" : "FOLLOWED_POST",
                    mine ? "笔记发布成功" : "关注的人发布了新笔记",
                    (mine ? "你发布了" : actorNames.get(blog.getUserId()) + " 发布了") + "《" + blog.getTitle() + "》",
                    blog.getImages(), "BLOG", blog.getId(),
                    blog.getCreateTime().atZone(clock.getZone()).toInstant().toEpochMilli(), null));
        }
        if (x != null && y != null) {
            for (Shop shop : nearby.find(x, y, 5, null, since, 0, LIMIT)) {
                if (shop.getCreateTime() == null) continue;
                messages.add(new ActivityMessageDTO("shop:" + shop.getId(), "NEW_SHOP", "附近有新商户",
                        shop.getName() + " · " + (shop.getAddress() == null ? "查看商户详情" : shop.getAddress()),
                        shop.getImages(), "SHOP", shop.getId(),
                        shop.getCreateTime().atZone(clock.getZone()).toInstant().toEpochMilli(), shop.getDistance()));
            }
        }
        return messages.stream().filter(m -> m.getCreatedAt() <= now)
                .sorted(Comparator.comparingLong(ActivityMessageDTO::getCreatedAt).reversed()
                        .thenComparing(ActivityMessageDTO::getId))
                .limit(LIMIT).collect(Collectors.toList());
    }
}
