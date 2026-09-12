package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;



@Slf4j
@Configuration
@EnableScheduling
public class OrderTaskExecutorConfig {
    //向Spring注册一个名为orderTaskExecutor的线程池 shutdown表示应用关闭时停止接收新任务并关闭线程池
    @Bean(name="orderTaskExecutor",destroyMethod = "shutdown")
    public ThreadPoolExecutor orderTaskExecutor(HmdpProperties properties){
        //读取application.yaml中的order-task配置。
        HmdpProperties.OrderTask config=properties.getOrderTask();
        //用于线程编号，AtomicInteger保证多个线程同时创建时编号不重复。
        AtomicInteger threadNumber=new AtomicInteger(1);
        //给线程命名。
        ThreadFactory threadFactory=runnable->{
            Thread thread=new Thread(
                    runnable,
                    "order-task-"+threadNumber.getAndIncrement()
            );
            //记录错误日志便于排查
            thread.setUncaughtExceptionHandler((t,e)->
                    log.error("订单异步线程异常，thread={}",t.getName(),e));
            return thread;
        };
        return new ThreadPoolExecutor(
                config.getCorePoolSize(),
                config.getMaximumPoolSize(),
                config.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getQueueCapacity()),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
