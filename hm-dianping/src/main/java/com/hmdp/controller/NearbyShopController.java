package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.NearbyShopService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NearbyShopController {
    private final NearbyShopService nearby;

    public NearbyShopController(NearbyShopService nearby) { this.nearby = nearby; }

    @GetMapping("/shop/nearby")
    public Result nearby(@RequestParam Double x, @RequestParam Double y,
                         @RequestParam(defaultValue = "5") Double radiusKm,
                         @RequestParam(required = false) Long typeId,
                         @RequestParam(defaultValue = "1") Integer current) {
        if (!NearbyShopService.validPosition(x, y)) return Result.fail("经纬度无效");
        if (!Double.isFinite(radiusKm) || radiusKm < 0.1 || radiusKm > 20)
            return Result.fail("查询范围须为0.1至20公里");
        if (current < 1 || current > 1000 || (typeId != null && typeId < 1))
            return Result.fail("分页或分类参数无效");
        return Result.ok(nearby.find(x, y, radiusKm, typeId, null, (current - 1) * 10, 10));
    }
}
