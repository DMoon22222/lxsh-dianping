-- Both keys use the same {phone} hash tag. 1: allowed, 0: cooldown, -1: 24h quota.
if redis.call('exists', KEYS[1]) == 1 then
    return 0
end
local count = tonumber(redis.call('get', KEYS[2]) or '0')
if count >= tonumber(ARGV[2]) then
    return -1
end
redis.call('set', KEYS[1], '1', 'EX', ARGV[1])
local total = redis.call('incr', KEYS[2])
if total == 1 then
    redis.call('expire', KEYS[2], 86400)
end
return 1
