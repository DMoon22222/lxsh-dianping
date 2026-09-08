package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderStatusDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.PendingOrderService;
import com.hmdp.mq.VoucherOrderMessage;
import com.hmdp.mq.VoucherOrderProducer;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillOrderStatus;
import com.hmdp.utils.SnowflakeIdGenerator;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Map;

@Service
@Slf4j
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private PendingOrderService pendingOrderService;

    /**
     * RabbitMQ订单消息生产者
     */
    @Resource
    private VoucherOrderProducer voucherOrderProducer;

    @Value("${hmdp.seckill.async-enabled:true}")
    private boolean seckillAsyncEnabled;

    private static final DefaultRedisScript<Long>
            SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT =
                new DefaultRedisScript<>();

        SECKILL_SCRIPT.setLocation(
                new ClassPathResource("seckill.lua")
        );

        SECKILL_SCRIPT.setResultType(
                Long.class
        );
    }

    /**
     * 用户秒杀优惠券
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = snowflakeIdGenerator.nextId();
        long now = System.currentTimeMillis();

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        "seckill:stock:" + voucherId,
                        "seckill:order:" + voucherId,
                        "seckill:pending",
                        "seckill:pending:data:" + orderId
                ),
                userId.toString(),
                voucherId.toString(),
                orderId.toString(),
                String.valueOf(now)
        );

        if (result == null) {
            return Result.fail("秒杀服务异常");
        }

        int code = result.intValue();
        if (code != 0) {
            return Result.fail(code == 1 ? "库存不足" : "不能重复下单");
        }

        if (!seckillAsyncEnabled) {
            return createOrderSynchronously(orderId, userId, voucherId);
        }

        VoucherOrderMessage orderMessage =
                new VoucherOrderMessage(orderId, userId, voucherId);

        try {
            voucherOrderProducer.sendOrderMessage(orderMessage);
        } catch (AmqpException e) {
            pendingOrderService.markSendFailed(
                    orderId,
                    "convertAndSend exception: " + e.getMessage()
            );
            log.error("发送秒杀订单消息异常，orderId={}", orderId, e);
            return Result.ok(orderId);
        }

        return Result.ok(orderId);
    }

    private Result createOrderSynchronously(Long orderId, Long userId, Long voucherId) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        try {
            createVouchOrder(voucherOrder);
            pendingOrderService.removePending(orderId);
            return Result.ok(orderId);
        } catch (DuplicateKeyException e) {
            pendingOrderService.restorePreDeduct(orderId, userId, voucherId);
            pendingOrderService.markFailed(orderId, "sync duplicate order: " + e.getMessage());
            log.warn("同步创建秒杀订单失败，用户重复下单，orderId={}", orderId, e);
            return Result.fail("不能重复下单");
        } catch (Exception e) {
            pendingOrderService.restorePreDeduct(orderId, userId, voucherId);
            pendingOrderService.markFailed(orderId, "sync create order failed: " + e.getMessage());
            log.error("同步创建秒杀订单异常，orderId={}", orderId, e);
            return Result.fail("秒杀服务异常");
        }
    }

    @Override
    public SeckillOrderStatusDTO querySeckillOrderStatus(Long orderId, Long userId) {
        Map<Object, Object> pendingData = pendingOrderService.getData(orderId);
        if (!pendingData.isEmpty()) {
            if (!belongsToUser(pendingData.get("userId"), userId)) {
                return notFound(orderId);
            }
            return buildStatus(orderId, pendingData);
        }

        VoucherOrder order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return notFound(orderId);
        }

        SeckillOrderStatusDTO status = new SeckillOrderStatusDTO();
        status.setOrderId(orderId);
        status.setStatus(SeckillOrderStatus.COMPLETED);
        status.setPipelineStatus("ORDER_CREATED");
        return status;
    }

    /**
     * RabbitMQ消费者调用的订单创建方法
     */
    @Override
    @Transactional
    public void createVouchOrder(
            VoucherOrder voucherOrder
    ) {

        /*
         * 1. 先插入订单
         *
         * 如果消息重复：
         * - 主键ID会冲突；
         * - 或user_id + voucher_id唯一索引冲突；
         * - 抛出DuplicateKeyException；
         * - 消费者直接ACK。
         */
        boolean saved = save(voucherOrder);

        if (!saved) {
            throw new IllegalStateException(
                    "订单保存失败"
            );
        }

        /*
         * 2. 扣减MySQL库存
         *
         * stock > 0 防止数据库超卖。
         */
        boolean stockSuccess =
                seckillVoucherService
                        .update()
                        .setSql(
                                "stock = stock - 1"
                        )
                        .eq(
                                "voucher_id",
                                voucherOrder
                                        .getVoucherId()
                        )
                        .gt(
                                "stock",
                                0
                        )
                        .update();

        if (!stockSuccess) {

            /*
             * 抛出异常后，
             * @Transactional会回滚前面的订单插入。
             */
            throw new IllegalStateException(
                    "数据库库存不足"
            );
        }
    }

    private SeckillOrderStatusDTO buildStatus(Long orderId, Map<Object, Object> data) {
        SeckillOrderStatusDTO status = new SeckillOrderStatusDTO();
        String pipelineStatus = asString(data.get("status"));

        status.setOrderId(orderId);
        status.setPipelineStatus(pipelineStatus);
        status.setAcceptedAt(readLong(data.get("createTime")));
        status.setConsumeStartedAt(readLong(data.get("consumeStartTime")));
        status.setDbCommittedAt(readLong(data.get("dbCommittedTime")));
        status.setQueueDelayMillis(readLong(data.get("queueDelayMillis")));
        status.setDbProcessMillis(readLong(data.get("dbProcessMillis")));
        status.setEndToEndMillis(readLong(data.get("endToEndMillis")));
        status.setFailureReason(asString(data.get("lastError")));

        if ("ORDER_CREATED".equals(pipelineStatus)) {
            status.setStatus(SeckillOrderStatus.COMPLETED);
        } else if ("FAILED".equals(pipelineStatus)) {
            status.setStatus(SeckillOrderStatus.FAILED);
        } else {
            status.setStatus(SeckillOrderStatus.PROCESSING);
        }
        return status;
    }

    private SeckillOrderStatusDTO notFound(Long orderId) {
        SeckillOrderStatusDTO status = new SeckillOrderStatusDTO();
        status.setOrderId(orderId);
        status.setStatus(SeckillOrderStatus.NOT_FOUND);
        return status;
    }

    private boolean belongsToUser(Object storedUserId, Long userId) {
        return storedUserId != null && String.valueOf(userId).equals(String.valueOf(storedUserId));
    }

    private Long readLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
