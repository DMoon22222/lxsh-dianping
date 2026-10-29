package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.ActivityMessageService;
import com.hmdp.service.NearbyShopService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityMessageController {
    private final ActivityMessageService messages;

    public ActivityMessageController(ActivityMessageService messages) { this.messages = messages; }

    @GetMapping("/messages")
    public Result recent(@RequestParam(required = false) Double x, @RequestParam(required = false) Double y) {
        if ((x != null || y != null) && !NearbyShopService.validPosition(x, y))
            return Result.fail("经纬度无效");
        return Result.ok(messages.recent(UserHolder.getUser().getId(), x, y));
    }
}
