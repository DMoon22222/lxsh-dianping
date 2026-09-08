package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.ratelimit.RateLimit;
import com.hmdp.ratelimit.RateLimitScope;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author Moon
 * @since 2026-07-13
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RateLimit(
            key = "'seckill:' + #p0",
            maxCount = "${hmdp.rate-limit.seckill.max-count:300}",
            windowSeconds = "${hmdp.rate-limit.seckill.window-seconds:1}",
            scope = RateLimitScope.GLOBAL,
            message = "当前抢购人数过多，请稍后再试"
    )
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 供客户端轮询异步订单的最终处理状态，也可用于端到端压测。
     */
    @GetMapping("status/{orderId}")
    public Result querySeckillOrderStatus(@PathVariable Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("未登录");
        }
        return Result.ok(voucherOrderService.querySeckillOrderStatus(orderId, user.getId()));
    }
}
