CREATE TABLE IF NOT EXISTS `tb_cache_invalidate_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cache_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '缓存名称',
  `cache_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务缓存键',
  `stage` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '0首次失效，1延迟二次失效',
  `status` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '0待处理，1处理中，2完成，3死亡',
  `retry_count` int unsigned NOT NULL DEFAULT '0' COMMENT '当前阶段重试次数',
  `next_retry_time` datetime NOT NULL COMMENT '下次执行或处理租约到期时间',
  `last_error` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近失败原因',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_outbox_due` (`status`,`next_retry_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
