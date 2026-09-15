-- KEYS[1] = seckill:stock:voucherId             -- 库存 key
-- KEYS[2] = seckill:reservation:voucherId       -- 用户 -> orderId 的预占 Hash key
-- KEYS[3] = seckill:order:voucherId              -- 旧版本已下单用户集合（兼容升级）
-- KEYS[4] = seckill:pending                      -- 待发送订单 ZSet
-- KEYS[5] = seckill:pending:data:{orderId}       -- 待发送订单详情 Hash
-- ARGV[1]: userId
-- ARGV[2]: voucherId
-- ARGV[3]: orderId
-- ARGV[4]: nowMillis
local stockKey = KEYS[1]
local reservationKey = KEYS[2]
local legacyOrderKey = KEYS[3]
local pendingKey = KEYS[4]
local pendingDataKey = KEYS[5]

local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local nowMillis = ARGV[4]

-- 1. 先检查已有 reservation。即使库存被其他请求耗尽，超时重试也应返回原订单号。
local existingOrderId = redis.call('HGET', reservationKey, userId)
if existingOrderId then
    -- 使用字符串返回，避免雪花订单号转换为 Lua number 后丢失精度。
    return 'EXISTS:' .. existingOrderId
end

-- 兼容升级前已写入 Set 的订单，避免旧资格在升级后被绕过。
if redis.call('SISMEMBER', legacyOrderKey, userId) == 1 then
    return 'LEGACY_EXISTS'
end

-- 2. 判断库存 GET seckill:stock:voucherId
local stock = redis.call('GET', stockKey)
if stock == false or tonumber(stock) <= 0 then
    return '1'
end

-- 3. 预扣库存 INCRBY seckill:stock:voucherId -1
redis.call('INCRBY', stockKey, -1)
-- 4、记录本次预占的所有者 HSET reservation userId orderId
redis.call('HSET', reservationKey, userId, orderId)

-- 5、记录待发送订单 ZADD seckill:pending {nowMillis} {orderId}
redis.call('ZADD',pendingKey,nowMillis,orderId)
-- 6、记录待发送订单详情 保存到Hash中 HSET seckill:pending:data:{orderId} orderId {orderId} userId {userId} voucherId {voucherId} status PENDING retryCount 0 createTime {nowMillis} updateTime {nowMillis} lastError ''
redis.call('HSET',pendingDataKey,
           'orderId',orderId,
           'userId',userId,
           'voucherId',voucherId,
           'status','PENDING',
           'retryCount','0',
           'createTime',nowMillis,
           'updateTime',nowMillis,
           'lastError','')
-- 7、设置过期时间 EXPIRE seckill:pending:data:{orderId} 86400
redis.call('EXPIRE',pendingDataKey,86400)

return '0'
