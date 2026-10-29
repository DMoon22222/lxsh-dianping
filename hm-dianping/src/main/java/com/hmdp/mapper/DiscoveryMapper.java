package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only discovery queries; all client values use bound parameters. */
public interface DiscoveryMapper {
    @Select({"<script>",
            "SELECT * FROM (SELECT s.*,",
            "6371008.8 * 2 * ASIN(SQRT(LEAST(1,",
            "POWER(SIN(RADIANS(s.y - #{y}) / 2), 2) +",
            "COS(RADIANS(#{y})) * COS(RADIANS(s.y)) *",
            "POWER(SIN(RADIANS(s.x - #{x}) / 2), 2)))) AS distance",
            "FROM tb_shop s WHERE s.x BETWEEN -180 AND 180 AND s.y BETWEEN -90 AND 90",
            "<if test='typeId != null'>AND s.type_id = #{typeId}</if>",
            "<if test='since != null'>AND s.create_time &gt;= #{since}</if>",
            ") nearby WHERE distance &lt;= #{radiusMeters}",
            "<choose><when test='since != null'>ORDER BY create_time DESC, id DESC</when>",
            "<otherwise>ORDER BY distance ASC, id ASC</otherwise></choose>",
            "LIMIT #{offset}, #{limit}",
            "</script>"})
    List<Shop> nearby(@Param("x") double x, @Param("y") double y,
                      @Param("radiusMeters") double radiusMeters, @Param("typeId") Long typeId,
                      @Param("since") LocalDateTime since, @Param("offset") int offset,
                      @Param("limit") int limit);

    // Old notes may receive new likes, so do not restrict this query by note creation time.
    @Select("SELECT id, user_id, title, images, create_time FROM tb_blog WHERE user_id = #{userId}")
    List<Blog> ownedNotes(@Param("userId") Long userId);

    @Select({"SELECT b.id, b.user_id, b.title, b.images, b.create_time FROM tb_blog b",
            "WHERE b.create_time >= #{since} AND (b.user_id = #{userId} OR EXISTS (",
            "SELECT 1 FROM tb_follow f WHERE f.user_id = #{userId}",
            "AND f.follow_user_id = b.user_id AND f.create_time <= b.create_time))",
            "ORDER BY b.create_time DESC, b.id DESC LIMIT #{limit}"})
    List<Blog> recentNotes(@Param("userId") Long userId, @Param("since") LocalDateTime since,
                           @Param("limit") int limit);
}
