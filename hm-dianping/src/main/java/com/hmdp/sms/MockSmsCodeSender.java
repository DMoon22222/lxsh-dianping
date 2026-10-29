package com.hmdp.sms;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MockSmsCodeSender implements SmsCodeSender {
    @Override
    public void sendCode(String phone, String code) {
        log.debug("模拟短信已处理，不会向手机发送短信；本地调试请从开发 Redis 获取验证码");
    }
}
