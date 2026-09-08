package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.mq.RabbitMqConstants.*;

/*
 * 处理订单消息的消费者
 * 它在整个链路里的位置是：
   用户请求秒杀
  -> Redis Lua 预扣库存、记录用户
  -> VoucherOrderProducer 发送 MQ 消息
  -> RabbitMQ 队列保存消息
  -> VoucherOrderConsumer 消费消息
  -> 调用 createVouchOrder()
  -> 写入 tb_voucher_order，扣减 tb_seckill_voucher 库存
 */
@Component
@Slf4j
public class VoucherOrderConsumer {

    /**
     * 最多重试3次
     */
    private static final long MAX_RETRY_COUNT = 3;
    private static final long FAILED_MESSAGE_CONFIRM_TIMEOUT_SECONDS = 5;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private PendingOrderService pendingOrderService;

    @RabbitListener(queues = ORDER_QUEUE)
    public void listenVoucherOrder(
            VoucherOrderMessage orderMessage,
            Message message,
            Channel channel
    ) throws IOException {

        long deliveryTag =
                message.getMessageProperties()
                        .getDeliveryTag();

        try {
            pendingOrderService.markConsuming(
                    orderMessage.getOrderId(),
                    System.currentTimeMillis()
            );
            VoucherOrder voucherOrder =
                    new VoucherOrder();

            voucherOrder.setId(
                    orderMessage.getOrderId()
            );

            voucherOrder.setUserId(
                    orderMessage.getUserId()
            );

            voucherOrder.setVoucherId(
                    orderMessage.getVoucherId()
            );

            /*
             * 调用Service事务方法 把秒杀订单写入MySQL，并扣减MySQL库存
            */
            voucherOrderService.createVouchOrder(voucherOrder);
            pendingOrderService.removePending(
                    orderMessage.getOrderId(),
                    System.currentTimeMillis()
            );
            /*
             * 数据库事务成功后再ACK
             */
            channel.basicAck(deliveryTag, false);

            log.info("秒杀订单创建成功，orderId={}", orderMessage.getOrderId());

        } catch (DuplicateKeyException e) {

            /*
             * 唯一索引冲突说明：
             * 这条消息已经被处理过。
             *
             * 重复消息不能继续重试，
             * 应直接确认。
             */
            pendingOrderService.removePending(orderMessage.getOrderId());
            channel.basicAck(deliveryTag, false);

            log.warn("订单重复消费，已被唯一索引拦截，orderId={}", orderMessage.getOrderId());

        } catch (Exception e) {

            long retryCount =
                    getRetryCount(message);

            log.error(
                    "处理秒杀订单失败，orderId={}，retryCount={}",
                    orderMessage.getOrderId(),
                    retryCount,
                    e
            );

            if (retryCount >= MAX_RETRY_COUNT) {

                /*
                 * 超过最大重试次数，
                 * 发送到最终失败队列。
                 */
                try {
                    sendToFailedQueueAndWaitForConfirm(orderMessage);

                    /*
                     * 确认原消息，
                     * 避免它继续无限重试。
                     */
                    channel.basicAck(
                            deliveryTag,
                            false
                    );

                    log.error(
                            "订单消息已进入失败队列，orderId={}",
                            orderMessage.getOrderId()
                    );

                } catch (Exception sendFailedException) {

                    if (sendFailedException instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }

                    /*
                     * 如果连失败队列也发送失败，
                     * 暂时重新放回原队列。
                     */
                    channel.basicNack(
                            deliveryTag,
                            false,
                            true
                    );

                    log.error(
                            "发送失败队列失败，orderId={}",
                            orderMessage.getOrderId(),
                            sendFailedException
                    );
                }

                return;
            }

            /*
             * 拒绝消息，并且不直接重新放回原队列。
             *
             * 因为主队列配置了死信交换机，
             * 消息会进入5秒重试队列。
             */
            channel.basicReject(deliveryTag, false);
        }
    }

    private void sendToFailedQueueAndWaitForConfirm(
            VoucherOrderMessage orderMessage
    ) throws Exception {
        Long orderId = orderMessage.getOrderId();
        CorrelationData correlationData = new CorrelationData(
                FAILED_CORRELATION_PREFIX + orderId
        );

        rabbitTemplate.convertAndSend(
                ORDER_FAILED_EXCHANGE,
                ORDER_FAILED_ROUTING_KEY,
                orderMessage,
                message -> {
                    message.getMessageProperties().setDeliveryMode(
                            MessageDeliveryMode.PERSISTENT
                    );
                    message.getMessageProperties().setMessageId(orderId.toString());
                    message.getMessageProperties().setHeader(
                            MESSAGE_PURPOSE_HEADER,
                            FAILED_MESSAGE_PURPOSE
                    );
                    return message;
                },
                correlationData
        );

        CorrelationData.Confirm confirm = correlationData.getFuture().get(
                FAILED_MESSAGE_CONFIRM_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );

        if (confirm == null || !confirm.isAck()) {
            String reason = confirm == null ? "confirm is null" : confirm.getReason();
            throw new AmqpException(
                    "失败订单消息未被Broker确认，orderId=" + orderId + "，reason=" + reason
            );
        }

        if (correlationData.getReturned() != null) {
            throw new AmqpException(
                    "失败订单消息无法路由，orderId=" + orderId
                            + "，replyText=" + correlationData.getReturned().getReplyText()
            );
        }
    }

    /**
     * 从RabbitMQ的x-death消息头中读取重试次数
     */
    @SuppressWarnings("unchecked")
    private long getRetryCount(Message message) {

        Object xDeathObject =
                message.getMessageProperties()
                        .getHeaders()
                        .get("x-death");

        if (!(xDeathObject instanceof List)) {
            return 0;
        }

        List<Map<String, Object>> xDeathList =
                (List<Map<String, Object>>) xDeathObject;

        for (Map<String, Object> xDeath : xDeathList) {

            String queue =
                    String.valueOf(xDeath.get("queue"));

            String reason =
                    String.valueOf(xDeath.get("reason"));

            if (ORDER_QUEUE.equals(queue)
                    && "rejected".equals(reason)) {

                Object count = xDeath.get("count");

                if (count instanceof Number) {
                    return ((Number) count).longValue();
                }
            }
        }

        return 0;
    }
}
