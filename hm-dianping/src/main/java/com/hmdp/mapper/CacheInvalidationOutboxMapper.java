package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.CacheInvalidationOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface CacheInvalidationOutboxMapper extends BaseMapper<CacheInvalidationOutbox> {

    @Select("SELECT id, cache_name, cache_key, stage, status, retry_count, "
            + "next_retry_time, last_error, create_time, update_time "
            + "FROM tb_cache_invalidate_outbox "
            + "WHERE status = 0 AND next_retry_time <= #{now} "
            + "ORDER BY id LIMIT #{limit}")
    List<CacheInvalidationOutbox> findDue(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    @Update("UPDATE tb_cache_invalidate_outbox "
            + "SET status = 1, next_retry_time = #{leaseDeadline}, last_error = NULL "
            + "WHERE id = #{id} AND status = 0")
    int claim(
            @Param("id") Long id,
            @Param("leaseDeadline") LocalDateTime leaseDeadline);

    @Update("UPDATE tb_cache_invalidate_outbox "
            + "SET status = 0, stage = 1, retry_count = 0, "
            + "next_retry_time = #{nextRetryTime}, last_error = NULL "
            + "WHERE id = #{id} AND status = 1")
    int scheduleDelayedStage(
            @Param("id") Long id,
            @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Update("UPDATE tb_cache_invalidate_outbox "
            + "SET status = 2, last_error = NULL "
            + "WHERE id = #{id} AND status = 1")
    int markCompleted(@Param("id") Long id);

    @Update("UPDATE tb_cache_invalidate_outbox "
            + "SET status = 0, retry_count = #{retryCount}, "
            + "next_retry_time = #{nextRetryTime}, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 1")
    int markRetry(
            @Param("id") Long id,
            @Param("retryCount") int retryCount,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("lastError") String lastError);

    @Update("UPDATE tb_cache_invalidate_outbox "
            + "SET status = 3, retry_count = #{retryCount}, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 1")
    int markDead(
            @Param("id") Long id,
            @Param("retryCount") int retryCount,
            @Param("lastError") String lastError);

    @Update("UPDATE tb_cache_invalidate_outbox "
            + "SET status = 0, last_error = 'processing lease expired' "
            + "WHERE status = 1 AND next_retry_time <= #{now}")
    int recoverExpiredClaims(@Param("now") LocalDateTime now);
}
