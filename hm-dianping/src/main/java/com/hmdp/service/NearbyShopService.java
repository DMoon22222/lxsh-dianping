package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.DiscoveryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NearbyShopService {
    private final DiscoveryMapper mapper;

    public NearbyShopService(DiscoveryMapper mapper) { this.mapper = mapper; }

    public static boolean validPosition(Double x, Double y) {
        return x != null && y != null && Double.isFinite(x) && Double.isFinite(y)
                && x >= -180 && x <= 180 && y >= -90 && y <= 90;
    }

    public List<Shop> find(double x, double y, double radiusKm, Long typeId,
                           LocalDateTime since, int offset, int limit) {
        return mapper.nearby(x, y, radiusKm * 1000, typeId, since, offset, limit);
    }
}
