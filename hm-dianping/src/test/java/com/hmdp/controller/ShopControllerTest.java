package com.hmdp.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopControllerTest {
    private ShopController controller;
    private ShopMapper mapper;
    private IShopService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new ShopController();
        service = mock(IShopService.class);
        mapper = mock(ShopMapper.class);
        when(service.query()).thenReturn(new QueryChainWrapper<>(mapper));
        when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(controller, "shopService", service);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keywordSearchesNameAreaAndAddressUsingParameters() {
        assertTrue(controller.queryShopByName("  广州 & KTV  ", 1, "score").getSuccess());
        ArgumentCaptor<Wrapper<Shop>> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), capture.capture());
        QueryWrapper<Shop> query = (QueryWrapper<Shop>) capture.getValue();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("name LIKE") && sql.contains("area LIKE") && sql.contains("address LIKE"));
        assertTrue(sql.contains("ORDER BY score DESC,id ASC"));
        assertFalse(sql.contains("广州"));
        assertEquals(3, query.getParamNameValuePairs().size());
        assertTrue(query.getParamNameValuePairs().values().stream()
                .allMatch(value -> "%广州 & KTV%".equals(value)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unrecognizedSortCannotBecomeSql() {
        controller.queryShopByName("", 1, "score;DROP TABLE tb_shop");
        ArgumentCaptor<Wrapper<Shop>> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), capture.capture());
        assertEquals("ORDER BY id ASC", capture.getValue().getSqlSegment().trim());
    }

    @Test
    void invalidPageOrOversizedKeywordDoesNotReachDatabase() {
        assertFalse(controller.queryShopByName("KTV", 0, "").getSuccess());
        assertFalse(controller.queryShopByName(new String(new char[101]).replace('\0', 'a'), 1, "").getSuccess());
        verifyNoInteractions(mapper);
    }
}
