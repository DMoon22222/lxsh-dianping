package com.hmdp.config;

import com.hmdp.mq.CacheInvalidateMessagePublisher;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecovererWithConfirms;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

import static com.hmdp.mq.RabbitMqConstants.*;

@Configuration
public class RabbitMqConfig {

    /**
     * 正常订单交换机。
     */
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(
                ORDER_EXCHANGE,
                true,
                false
        );
    }

    /**
     * 正常订单队列。
     *
     * 消费失败后，消息进入重试交换机。
     */
    @Bean
    public Queue orderQueue() {
        return QueueBuilder
                .durable(ORDER_QUEUE)
                .deadLetterExchange(ORDER_RETRY_EXCHANGE)
                .deadLetterRoutingKey(ORDER_RETRY_ROUTING_KEY)
                .build();
    }

    /**
     * 正常订单队列绑定。
     */
    @Bean
    public Binding orderBinding() {
        return BindingBuilder
                .bind(orderQueue())
                .to(orderExchange())
                .with(ORDER_ROUTING_KEY);
    }

    /**
     * 订单重试交换机。
     */
    @Bean
    public DirectExchange orderRetryExchange() {
        return new DirectExchange(
                ORDER_RETRY_EXCHANGE,
                true,
                false
        );
    }

    /**
     * 订单重试队列。
     *
     * 消息在这里等待 5 秒，然后重新回到正常订单队列。
     */
    @Bean
    public Queue orderRetryQueue() {
        return QueueBuilder
                .durable(ORDER_RETRY_QUEUE)
                .ttl(5000)
                .deadLetterExchange(ORDER_EXCHANGE)
                .deadLetterRoutingKey(ORDER_ROUTING_KEY)
                .build();
    }

    /**
     * 订单重试队列绑定。
     */
    @Bean
    public Binding orderRetryBinding() {
        return BindingBuilder
                .bind(orderRetryQueue())
                .to(orderRetryExchange())
                .with(ORDER_RETRY_ROUTING_KEY);
    }

    /**
     * 订单最终失败交换机。
     */
    @Bean
    public DirectExchange orderFailedExchange() {
        return new DirectExchange(
                ORDER_FAILED_EXCHANGE,
                true,
                false
        );
    }

    /**
     * 订单最终失败队列。
     */
    @Bean
    public Queue orderFailedQueue() {
        return QueueBuilder
                .durable(ORDER_FAILED_QUEUE)
                .build();
    }

    /**
     * 订单最终失败队列绑定。
     */
    @Bean
    public Binding orderFailedBinding() {
        return BindingBuilder
                .bind(orderFailedQueue())
                .to(orderFailedExchange())
                .with(ORDER_FAILED_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderFailedParkingExchange() {
        return new DirectExchange(
                ORDER_FAILED_PARKING_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public Queue orderFailedParkingQueue() {
        return QueueBuilder
                .durable(ORDER_FAILED_PARKING_QUEUE)
                .build();
    }

    @Bean
    public Binding orderFailedParkingBinding() {
        return BindingBuilder
                .bind(orderFailedParkingQueue())
                .to(orderFailedParkingExchange())
                .with(ORDER_FAILED_PARKING_ROUTING_KEY);
    }

    /**
     * 最终失败消息首次处理失败后最多再重试 3 次；全部失败后将原消息
     * 可靠发布到停车交换机，避免在失败队列中无限重新入队。
     */
    @Bean
    public MessageRecoverer failedOrderMessageRecoverer(
            @Qualifier("rabbitTemplate") RabbitTemplate rabbitTemplate) {
        RepublishMessageRecovererWithConfirms republishRecoverer =
                new RepublishMessageRecovererWithConfirms(
                        rabbitTemplate,
                        ORDER_FAILED_PARKING_EXCHANGE,
                        ORDER_FAILED_PARKING_ROUTING_KEY,
                        CachingConnectionFactory.ConfirmType.CORRELATED
                );
        republishRecoverer.setConfirmTimeout(5000);

        return (message, cause) -> {
            republishRecoverer.recover(message, cause);
            throw new AmqpRejectAndDontRequeueException(
                    "失败消息已确认转发到停车队列",
                    cause
            );
        };
    }

    @Bean
    public RetryOperationsInterceptor failedOrderRetryInterceptor(
            @Qualifier("failedOrderMessageRecoverer") MessageRecoverer failedOrderMessageRecoverer) {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(4)
                .backOffOptions(1000, 2.0, 5000)
                .recoverer(failedOrderMessageRecoverer)
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory failedOrderRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            @Qualifier("failedOrderRetryInterceptor")
            RetryOperationsInterceptor failedOrderRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(true);
        factory.setPrefetchCount(1);
        factory.setAdviceChain(failedOrderRetryInterceptor);
        return factory;
    }
    @Bean
    public FanoutExchange cacheInvalidateExchange() {
        return new FanoutExchange(
                CACHE_INVALIDATE_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public Queue cacheInvalidateQueue(
            @Value("${hmdp.instance-id:${spring.application.name}-${server.port}}")
            String instanceId) {
        return QueueBuilder
                .nonDurable(CACHE_INVALIDATE_QUEUE_PREFIX + instanceId)
                .exclusive()
                .autoDelete()
                .build();
    }

    @Bean
    public Binding cacheInvalidateBinding(
            @Qualifier("cacheInvalidateQueue") Queue cacheInvalidateQueue,
            @Qualifier("cacheInvalidateExchange") FanoutExchange cacheInvalidateExchange) {
        return BindingBuilder
                .bind(cacheInvalidateQueue)
                .to(cacheInvalidateExchange);
    }

    @Bean
    public DirectExchange cacheInvalidateParkingExchange() {
        return new DirectExchange(
                CACHE_INVALIDATE_PARKING_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public Queue cacheInvalidateParkingQueue() {
        return QueueBuilder
                .durable(CACHE_INVALIDATE_PARKING_QUEUE)
                .build();
    }

    @Bean
    public Binding cacheInvalidateParkingBinding() {
        return BindingBuilder
                .bind(cacheInvalidateParkingQueue())
                .to(cacheInvalidateParkingExchange())
                .with(CACHE_INVALIDATE_PARKING_ROUTING_KEY);
    }

    @Bean
    public MessageRecoverer cacheInvalidateMessageRecoverer(
            CacheInvalidateMessagePublisher cacheInvalidateMessagePublisher) {
        return new RepublishMessageRecovererWithConfirms(
                cacheInvalidateMessagePublisher.getRabbitTemplate(),
                CACHE_INVALIDATE_PARKING_EXCHANGE,
                CACHE_INVALIDATE_PARKING_ROUTING_KEY,
                CachingConnectionFactory.ConfirmType.CORRELATED
        );
    }

    @Bean
    public RetryOperationsInterceptor cacheInvalidateRetryInterceptor(
            @Qualifier("cacheInvalidateMessageRecoverer")
            MessageRecoverer cacheInvalidateMessageRecoverer) {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(4)
                .backOffOptions(1000, 2.0, 5000)
                .recoverer(cacheInvalidateMessageRecoverer)
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory cacheInvalidateRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            @Qualifier("cacheInvalidateRetryInterceptor")
            RetryOperationsInterceptor cacheInvalidateRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(20);
        factory.setAdviceChain(cacheInvalidateRetryInterceptor);
        return factory;
    }

    /**
     * 使用 JSON 序列化保存消息。
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
