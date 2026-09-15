-- KEYS 均带相同的 {voucherId} Hash Tag，以兼容 Redis Cluster 多 Key Lua。
local stockKey = KEYS[1]
local reservationKey = KEYS[2]
local legacyOrderKey = KEYS[3]
local pendingKey = KEYS[4]

local userId = ARGV[1]
local orderId = ARGV[2]

-- 只有当前 reservation 仍属于本 orderId 才能补偿。
-- 这同时提供幂等性：成功补偿后 HDEL，后续重复消息不会再次加库存；
-- 若用户已经拿到新的 reservation，旧 orderId 也不会误删新记录。
if redis.call('HGET', reservationKey, userId) == orderId then
    redis.call('INCRBY',stockKey,1)
    redis.call('HDEL',reservationKey,userId)
    redis.call('ZREM',pendingKey,orderId)
    return 1
end

-- 兼容升级前已经预扣、但尚未完成的订单。只有不存在新 reservation 时，
-- 才允许按旧 Set 释放资格，绝不覆盖新版本 reservation。
if not redis.call('HGET', reservationKey, userId)
        and redis.call('SISMEMBER', legacyOrderKey, userId) == 1 then
    redis.call('INCRBY',stockKey,1)
    redis.call('SREM',legacyOrderKey,userId)
    redis.call('ZREM',pendingKey,orderId)
    return 1
end

-- 预占已被释放、已由其他订单持有或根本不存在，均不可修改库存。
redis.call('ZREM',pendingKey,orderId)

return 0
