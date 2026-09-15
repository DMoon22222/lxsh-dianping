package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;

import static com.hmdp.mq.RabbitMqConstants.ORDER_FAILED_QUEUE;

/**
 * 处理最终失败的订单消息：数据库中已存在订单时只清理 Pending；
 * 数据库中不存在订单时恢复 Redis 预扣状态并记录最终失败。
 */
@Component
@Slf4j
public class VoucherOrderFailedConsumer {

    private static final DefaultRedisScript<Long> RESTORE_SCRIPT;

    static {
        RESTORE_SCRIPT = new DefaultRedisScript<>();
        RESTORE_SCRIPT.setLocation(new ClassPathResource("seckill_restore.lua"));
        RESTORE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PendingOrderService pendingOrderService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(
            queues = ORDER_FAILED_QUEUE,
            containerFactory = "failedOrderRabbitListenerContainerFactory"
    )
    public void listenFailedOrder(VoucherOrderMessage orderMessage,
                                  Message message,
                                  Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long orderId = orderMessage.getOrderId();

        try {
            boolean exists = voucherOrderService.getById(orderId) != null;

            if (exists) {
                pendingOrderService.removePending(orderId);
                channel.basicAck(tag, false);
                log.info("失败队列订单已在数据库存在，已清理待处理记录，orderId={}", orderId);
                return;
            }

            Long restored = stringRedisTemplate.execute(
                    RESTORE_SCRIPT,
                    Arrays.asList(
                            "seckill:stock:" + orderMessage.getVoucherId(),
                            "seckill:reservation:" + orderMessage.getVoucherId(),
                            "seckill:order:" + orderMessage.getVoucherId(),
                            PendingOrderService.PENDING_KEY
                    ),
                    orderMessage.getUserId().toString(),
                    orderId.toString()
            );

            if (restored == null) {
                throw new IllegalStateException("Redis恢复脚本未返回结果，orderId=" + orderId);
            }

            pendingOrderService.markFailed(
                    orderId,
                    "consume failed, redis restored=" + restored
            );
            channel.basicAck(tag, false);
            log.info("失败队列订单处理完成，orderId={}, redisRestored={}", orderId, restored);
        } catch (Exception e) {
            // 不在这里 NACK；异常交给专用监听容器做有限次数重试。
            log.error("处理订单失败队列异常，将由有限重试机制处理，orderId={}", orderId, e);
            throw e;
        }
    }
}
