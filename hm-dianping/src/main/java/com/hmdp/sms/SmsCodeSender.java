package com.hmdp.sms;

public interface SmsCodeSender {
    /** Returns only after the provider accepts the request; receipt is asynchronous. */
    void sendCode(String phone, String code);
}
