package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderStatusDTO;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Moon
 * @since 2026-07-13
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 根据id查询优惠券信息并判断能否使用
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建优惠券订单
     *
     * @param voucherOrder
     */
    void createVouchOrder(VoucherOrder voucherOrder);

    /** 查询当前用户的秒杀订单异步处理状态。 */
    SeckillOrderStatusDTO querySeckillOrderStatus(Long orderId, Long userId);
}
