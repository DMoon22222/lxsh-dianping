package com.hmdp.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import static com.hmdp.mq.RabbitMqConstants.FAILED_CORRELATION_PREFIX;
import static com.hmdp.mq.RabbitMqConstants.FAILED_MESSAGE_PURPOSE;
import static com.hmdp.mq.RabbitMqConstants.MESSAGE_PURPOSE_HEADER;
import static com.hmdp.mq.RabbitMqConstants.ORDER_EXCHANGE;
import static com.hmdp.mq.RabbitMqConstants.ORDER_ROUTING_KEY;

/*
 * 订单消息生产者
 * 用户秒杀请求
  -> Redis Lua 判断库存/一人一单
  -> 生成订单消息 VoucherOrderMessage
  -> VoucherOrderProducer 发送到 RabbitMQ
  -> VoucherOrderConsumer 消费消息
  -> 创建数据库订单
 */
@Component
@Slf4j
public class VoucherOrderProducer
        implements RabbitTemplate.ConfirmCallback,
        RabbitTemplate.ReturnCallback {

    private static final String ORDER_CORRELATION_SEPARATOR = ":";

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private PendingOrderService pendingOrderService;

    @PostConstruct
    public void init() {
        // RabbitMQ是否成功接收消息
        rabbitTemplate.setConfirmCallback(this);

        // 消息是否成功路由到队列
        rabbitTemplate.setReturnCallback(this);
    }

    /**
     * 发送秒杀订单消息
     */
    public void sendOrderMessage(VoucherOrderMessage orderMessage) {

        String messageId = orderMessage.getOrderId()
                + ORDER_CORRELATION_SEPARATOR
                + orderMessage.getVoucherId();

        CorrelationData correlationData =
                new CorrelationData(messageId);

        rabbitTemplate.convertAndSend(
                ORDER_EXCHANGE,
                ORDER_ROUTING_KEY,
                orderMessage,
                message -> {
                    // 设置消息持久化
                    message.getMessageProperties()
                            .setDeliveryMode(
                                    MessageDeliveryMode.PERSISTENT
                            );

                    // 设置消息唯一ID
                    message.getMessageProperties()
                            .setMessageId(messageId);

                    return message;
                },
                correlationData
        );
    }

    /**
     * Broker确认回调 交换机是否成功接收到消息
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            if (ack) {
                log.debug("RabbitMQ Confirm 回调未携带业务关联数据，ack=true");
            } else {
                log.error("RabbitMQ Confirm 回调缺少 CorrelationData，ack=false，cause={}", cause);
            }
            return;
        }

        String correlationId = correlationData.getId();
        if (correlationId.startsWith(FAILED_CORRELATION_PREFIX)) {
            if (ack) {
                log.debug("RabbitMQ Broker 已接收失败转发消息，correlationId={}", correlationId);
            } else {
                log.error("RabbitMQ Broker 拒绝失败转发消息，correlationId={}，cause={}",
                        correlationId, cause);
            }
            return;
        }

        OrderCorrelation correlation = parseOrderCorrelation(correlationId);
        if (correlation == null) {
            log.warn("RabbitMQ Confirm 回调包含未知 CorrelationData，correlationId={}，ack={}，cause={}",
                    correlationId, ack, cause);
            return;
        }

        if (ack) {
            pendingOrderService.markSendSuccess(correlation.voucherId, correlation.orderId);
            log.debug("RabbitMQ Broker 已接收订单消息，orderId={}", correlation.orderId);
            return;
        }

        pendingOrderService.markSendFailed(correlation.voucherId, correlation.orderId, cause);
        log.error("RabbitMQ Broker 接收订单消息失败，orderId={}，cause={}", correlation.orderId, cause);
    }

    /**
     * 消息无法路由到队列时退回生产者 消息是否成功路由到队列
     */
    @Override
    public void returnedMessage(Message message, int replyCode, String replyText, String exchange, String routingKey) {
        Object purpose = message.getMessageProperties()
                .getHeaders()
                .get(MESSAGE_PURPOSE_HEADER);
        if (FAILED_MESSAGE_PURPOSE.equals(String.valueOf(purpose))) {
            log.error("RabbitMQ 失败转发消息无法路由，messageId={}，replyCode={}，replyText={}，exchange={}，routingKey={}",
                    message.getMessageProperties().getMessageId(),
                    replyCode,
                    replyText,
                    exchange,
                    routingKey);
            return;
        }

        //将RabbitMQ消息反序列化
        Object converted = rabbitTemplate.getMessageConverter().fromMessage(message);
        if (!(converted instanceof VoucherOrderMessage)) {
            log.error("RabbitMQ Return 消息反序列化失败，replyCode={}，replyText={}，message={}",
                    replyCode, replyText, message);
            return;
        }

        VoucherOrderMessage orderMessage = (VoucherOrderMessage) converted;
        String reason = "return replyCode=" + replyCode
                + ", replyText=" + replyText
                + ", exchange=" + exchange
                + ", routingKey=" + routingKey;

        pendingOrderService.markRouteFailed(orderMessage, reason);
        log.error("RabbitMQ 订单消息无法路由，orderId={}，{}", orderMessage.getOrderId(), reason);
    }

    private OrderCorrelation parseOrderCorrelation(String correlationId) {
        String[] parts = correlationId.split(ORDER_CORRELATION_SEPARATOR, 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            return new OrderCorrelation(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class OrderCorrelation {
        private final Long orderId;
        private final Long voucherId;

        private OrderCorrelation(Long orderId, Long voucherId) {
            this.orderId = orderId;
            this.voucherId = voucherId;
        }
    }
}


