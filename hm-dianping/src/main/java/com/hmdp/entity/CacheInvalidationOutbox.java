package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_cache_invalidate_outbox")
public class CacheInvalidationOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String cacheName;

    private String cacheKey;

    /** 0: first invalidation, 1: delayed second invalidation. */
    private Integer stage;

    /** 0: pending, 1: processing, 2: completed, 3: dead. */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
