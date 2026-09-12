package com.hmdp.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderCreatedEvent extends ApplicationEvent{
    private final Long orderId;
    private final Long userId;
    private final Long voucherId;
    private final long createdAt;
    public OrderCreatedEvent(
            Object source,
            Long orderId,
            Long userId,
            Long voucherId,
            long createdAt
    ) {
        super(source);
        this.orderId = orderId;
        this.userId = userId;
        this.voucherId = voucherId;
        this.createdAt = createdAt;
    }
}
