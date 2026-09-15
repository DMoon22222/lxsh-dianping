package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Map;
import java.util.Set;
import java.util.List;

@Component
@Slf4j
public class PendingOrderRepublishTask {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_REPUBLISH_COUNT = 10;

    @Resource
    private PendingOrderService pendingOrderService;

    @Resource
    private VoucherOrderProducer voucherOrderProducer;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Scheduled(fixedDelay = 10000)
    public void republishPendingOrders() {
        long now = System.currentTimeMillis();
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        for (SeckillVoucher voucher : vouchers) {
            Long voucherId = voucher.getVoucherId();
            Set<String> orderIds = pendingOrderService.listDueOrderIds(voucherId, now, BATCH_SIZE);
            if (CollectionUtils.isEmpty(orderIds)) {
                continue;
            }

            for (String orderIdText : orderIds) {
                Long orderId = Long.valueOf(orderIdText);
                try {
                    republishOne(voucherId, orderId);
                } catch (Exception e) {
                    pendingOrderService.markSendFailed(
                            voucherId,
                            orderId,
                            "republish exception: " + e.getMessage()
                    );
                    log.error("补偿重投订单消息异常，orderId={}", orderId, e);
                }
            }
        }
    }

    private void republishOne(Long voucherId, Long orderId) {
        boolean exists = voucherOrderService.getById(orderId) != null;

        if (exists) {
            pendingOrderService.removePending(voucherId, orderId);
            log.info("PENDING 订单已在数据库存在，清理待重投记录，orderId={}", orderId);
            return;
        }

        Map<Object, Object> data = pendingOrderService.getData(voucherId, orderId);
        if (data == null || data.isEmpty()) {
            pendingOrderService.markFailed(voucherId, orderId, "pending data missing");
            return;
        }

        int retryCount = parseInt(data.get("retryCount"));
        if (retryCount >= MAX_REPUBLISH_COUNT) {
            pendingOrderService.markFailed(voucherId, orderId, "republish retry exhausted");
            log.error("订单消息重投超过上限，转入 FAILED，orderId={}", orderId);
            return;
        }

        Long userId = Long.valueOf(String.valueOf(data.get("userId")));
        pendingOrderService.beforeRepublish(voucherId, orderId);
        voucherOrderProducer.sendOrderMessage(new VoucherOrderMessage(orderId, userId, voucherId));

        log.warn("已重投 PENDING 订单消息，orderId={}，retryCount={}", orderId, retryCount + 1);
    }

    private int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }
    /**
     * 这个类解决的核心问题
     * 它主要解决生产者发送消息时的可靠性空档：
     * Lua已经扣减Redis库存
     *         ↓
     * 用户已经被记录为下过单
     *         ↓
     * RabbitMQ消息却没有成功进入消费链路
     *         ↓
     * 数据库订单无法创建
     * 通过 Redis Pending 记录和定时扫描，即使出现以下问题，订单消息也有机会恢复：
     * RabbitMQ 临时不可用。
     * Broker 返回 Confirm NACK。
     * 交换机或 routing key 配置错误。
     * 发送时发生网络异常。
     * 服务在发送前后突然宕机。
     * Confirm/Return 回调处理异常。
     */
}
