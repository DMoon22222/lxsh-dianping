package com.hmdp.mq;

import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hmdp.mq.RabbitMqConstants.CACHE_INVALIDATE_EXCHANGE;

@Component
public class CacheInvalidateMessagePublisher {

    @Resource
    private ConnectionFactory connectionFactory;

    @Resource
    private MessageConverter messageConverter;

    private RabbitTemplate rabbitTemplate;

    @Value("${hmdp.cache.outbox.confirm-timeout-ms:5000}")
    private long confirmTimeoutMs;

    @PostConstruct
    public void init() {
        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        rabbitTemplate.setMandatory(true);
    }

    public RabbitTemplate getRabbitTemplate() {
        return rabbitTemplate;
    }

    public void send(Long eventId, CacheInvalidateMessage cacheMessage) {
        String messageId = "cache-invalidate:" + eventId + ":" + cacheMessage.getKey();
        CorrelationData correlationData = new CorrelationData(messageId);

        rabbitTemplate.convertAndSend(
                CACHE_INVALIDATE_EXCHANGE,
                "",
                cacheMessage,
                message -> {
                    message.getMessageProperties().setMessageId(messageId);
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                },
                correlationData
        );

        CorrelationData.Confirm confirm;
        try {
            confirm = correlationData.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new CacheInvalidationException("缓存失效消息Confirm超时，eventId=" + eventId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheInvalidationException("等待缓存失效消息Confirm被中断，eventId=" + eventId, e);
        } catch (Exception e) {
            throw new CacheInvalidationException("等待缓存失效消息Confirm失败，eventId=" + eventId, e);
        }

        if (!confirm.isAck()) {
            throw new CacheInvalidationException(
                    "Broker拒绝缓存失效消息，eventId=" + eventId + "，reason=" + confirm.getReason());
        }
        if (correlationData.getReturned() != null) {
            throw new CacheInvalidationException(
                    "缓存失效消息无法路由，eventId=" + eventId
                            + "，reply=" + correlationData.getReturned().getReplyText());
        }
    }
}
