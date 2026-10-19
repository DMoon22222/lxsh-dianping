package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.mq.CacheInvalidationOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ShopServiceImplTest {

    private CacheInvalidationOutboxService outboxService;
    private ShopServiceImpl service;

    @BeforeEach
    void setUp() {
        outboxService = mock(CacheInvalidationOutboxService.class);
        service = spy(new ShopServiceImpl());
        ReflectionTestUtils.setField(
                service,
                "cacheInvalidationOutboxService",
                outboxService
        );
    }

    @Test
    void zeroRowUpdateIsTreatedAsFailure() {
        Shop shop = new Shop().setId(99L).setName("missing");
        doReturn(false).when(service).updateById(any(Shop.class));

        assertThrows(IllegalStateException.class, () -> service.update(shop));

        verifyNoInteractions(outboxService);
    }

    @Test
    void successfulUpdateRecordsOutboxEvent() {
        Shop shop = new Shop().setId(1L).setName("new-name");
        doReturn(true).when(service).updateById(any(Shop.class));

        service.update(shop);

        verify(outboxService).recordShopInvalidation(1L);
    }
}
