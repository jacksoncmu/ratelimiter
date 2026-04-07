-- Token Bucket Rate Limiter
-- KEYS[1]  = bucket key (e.g. "rate_limit:user-123")
-- ARGV[1]  = bucket capacity (max tokens)
-- ARGV[2]  = refill rate in tokens/second
-- ARGV[3]  = current time in milliseconds
--
-- Returns: "1:<remaining>" if allowed, "0:0" if rejected

local key         = KEYS[1]
local capacity    = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now         = tonumber(ARGV[3])

local data        = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens      = tonumber(data[1])
local last_refill = tonumber(data[2])

-- First request: initialise the bucket
if tokens == nil then
    local remaining = capacity - 1
    redis.call('HMSET', key, 'tokens', remaining, 'last_refill', now)
    redis.call('EXPIRE', key, 120)
    return "1:" .. tostring(remaining)
end

-- Refill tokens proportional to elapsed time
local elapsed   = (now - last_refill) / 1000.0
local refilled  = math.min(capacity, tokens + elapsed * refill_rate)

if refilled >= 1 then
    local new_tokens = refilled - 1
    redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
    redis.call('EXPIRE', key, 120)
    return "1:" .. tostring(math.floor(new_tokens))
else
    -- Not enough tokens yet; persist updated (fractional) count without consuming
    redis.call('HMSET', key, 'tokens', refilled, 'last_refill', now)
    redis.call('EXPIRE', key, 120)
    return "0:0"
end
