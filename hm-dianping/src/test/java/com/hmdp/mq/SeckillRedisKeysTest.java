package com.hmdp.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeckillRedisKeysTest {

    @Test
    void shouldUseVoucherIdAsTheSameRedisClusterHashTag() {
        Long voucherId = 10L;
        String tag = "{" + voucherId + "}";

        assertEquals(tag, hashTag(SeckillRedisKeys.stockKey(voucherId)));
        assertEquals(tag, hashTag(SeckillRedisKeys.reservationKey(voucherId)));
        assertEquals(tag, hashTag(SeckillRedisKeys.legacyOrderKey(voucherId)));
        assertEquals(tag, hashTag(SeckillRedisKeys.pendingKey(voucherId)));
        assertEquals(tag, hashTag(SeckillRedisKeys.pendingDataKey(voucherId, 10001L)));
    }

    private String hashTag(String key) {
        int start = key.indexOf('{');
        int end = key.indexOf('}', start + 1);
        return key.substring(start, end + 1);
    }
}
