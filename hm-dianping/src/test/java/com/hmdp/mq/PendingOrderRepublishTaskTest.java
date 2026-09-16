package com.hmdp.mq;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingOrderRepublishTaskTest {

    @Test
    void shouldRestoreRedisPreDeductBeforeMarkingRetryExhaustedOrderFailed() {
        PendingOrderService pendingOrderService = mock(PendingOrderService.class);
        VoucherOrderProducer voucherOrderProducer = mock(VoucherOrderProducer.class);
        IVoucherOrderService voucherOrderService = mock(IVoucherOrderService.class);
        ISeckillVoucherService seckillVoucherService = mock(ISeckillVoucherService.class);

        PendingOrderRepublishTask task = new PendingOrderRepublishTask();
        ReflectionTestUtils.setField(task, "pendingOrderService", pendingOrderService);
        ReflectionTestUtils.setField(task, "voucherOrderProducer", voucherOrderProducer);
        ReflectionTestUtils.setField(task, "voucherOrderService", voucherOrderService);
        ReflectionTestUtils.setField(task, "seckillVoucherService", seckillVoucherService);

        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(1L);
        when(seckillVoucherService.list()).thenReturn(Collections.singletonList(voucher));
        when(pendingOrderService.listDueOrderIds(eq(1L), org.mockito.ArgumentMatchers.anyLong(), eq(100)))
                .thenReturn(Collections.singleton("10"));
        when(voucherOrderService.getById(10L)).thenReturn(null);

        Map<Object, Object> data = new HashMap<>();
        data.put("retryCount", "10");
        data.put("userId", "42");
        when(pendingOrderService.getData(1L, 10L)).thenReturn(data);
        when(pendingOrderService.restorePreDeduct(10L, 42L, 1L)).thenReturn(1L);

        task.republishPendingOrders();

        verify(pendingOrderService).restorePreDeduct(10L, 42L, 1L);
        verify(pendingOrderService).markFailed(eq(1L), eq(10L), contains("redis restored=1"));
        verify(pendingOrderService, never()).beforeRepublish(1L, 10L);
        verify(voucherOrderProducer, never()).sendOrderMessage(org.mockito.ArgumentMatchers.any());
    }
}
