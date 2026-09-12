package com.hmdp.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

//创建事件监听器,订单事务提交成功后接收“订单已创建”事件，并把非核心统计任务交给专用线程池处理，并定时输出线程池运行状态
@Slf4j
@Component
public class OrderCreatedEventListener {
    private static final String DAILY_ORDER_COUNT_KEY=
            "seckill:analytics:order:created:";
    @Resource
    @Qualifier("orderTaskExecutor")
    private ThreadPoolExecutor orderTaskExecutor;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //数据库事务真正提交成功才会触发监听器，下单或者扣库存失败或回滚时，不会统计成功订单
    @TransactionalEventListener(phase= TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event){
        try {
            orderTaskExecutor.execute(() -> handle(event));
        } catch (RejectedExecutionException e) {
            // CallerRunsPolicy 会在满载时执行任务；这里主要处理应用关闭后的拒绝。
            log.warn("订单后置任务提交失败，orderId={}", event.getOrderId(), e);
        }
    }

    /**
     * 在线程池工作线程中执行。任务失败只记录日志，不影响已经提交的订单事务。
     * 将当天成功创建的订单数累加到Redis
     */
    private void handle(OrderCreatedEvent event) {
        try {
            String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            String key = DAILY_ORDER_COUNT_KEY + date;

            stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, 3, TimeUnit.DAYS);

            log.info(
                    "订单后置任务完成，orderId={}, userId={}, voucherId={}",
                    event.getOrderId(),
                    event.getUserId(),
                    event.getVoucherId()
            );
        } catch (Exception e) {
            // 即使 CallerRunsPolicy 让消费者线程执行，也不能把异常抛回消费者。
            log.error("订单后置任务处理失败，orderId={}", event.getOrderId(), e);
        }
    }
    @Scheduled(fixedDelayString =
            "${hmdp.order-task.monitor-interval-ms:30000}")
    public void logPoolMetrics() {
        log.info(
                "订单线程池状态：poolSize={}, active={}, completed={}, queueSize={}, queueRemaining={}",
                orderTaskExecutor.getPoolSize(),
                orderTaskExecutor.getActiveCount(),
                orderTaskExecutor.getCompletedTaskCount(),
                orderTaskExecutor.getQueue().size(),
                orderTaskExecutor.getQueue().remainingCapacity()
        );
    }
}
