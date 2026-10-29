package com.hmdp.sms;

public class SmsSendException extends RuntimeException {
    public SmsSendException() {
        // SDK exception messages may contain request parameters or credentials.
        super("短信发送失败，请稍后重试");
    }
}
