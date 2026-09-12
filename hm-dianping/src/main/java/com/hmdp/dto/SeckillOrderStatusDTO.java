package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀订单的异步处理状态。
 *
 * <p>时间字段均为 Unix epoch millisecond。它们用于压测观测，
 * 不参与订单正确性判断。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderStatusDTO {

    private Long orderId;

    /** PROCESSING、COMPLETED、FAILED 或 NOT_FOUND。 */
    private String status;

    /** Pending Hash 中保存的内部链路状态，例如 SENT、CONSUMING。 */
    private String pipelineStatus;

    /** Redis Lua 成功预扣并登记 Pending 的时间。 */
    private Long acceptedAt;

    /** RabbitMQ 消费者首次开始处理消息的时间。 */
    private Long consumeStartedAt;

    /** MySQL 创建订单事务成功返回后的时间。 */
    private Long dbCommittedAt;

    private Long queueDelayMillis;
    private Long dbProcessMillis;
    private Long endToEndMillis;
    private String failureReason;
}
