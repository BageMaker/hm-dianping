-- 参数
-- 1.优惠券id:voucher_id
local voucher_id = ARGV[1]
-- 2.用户id:user_id
local user_id = ARGV[2]

--redis中优惠券库存的key
local voucher_stock_key = "seckill:stock:" .. voucher_id
-- redis中订单的key
local order_key = "seckill:order:" .. voucher_id

-- 根据优惠券库存判断库存是否充足
if(tonumber(redis.call("get", voucher_stock_key) <= 0 ) then
    -- 库存不足，返回1
    return 1
end
-- 库存充足
-- 判断该用户是否购买过
if(redis.call("sismember", order_key, user_id) == 1) then
    -- 购买过，返回2
    return 2
end
-- 没购买过
-- 库存减1
redis.call("incrby", voucher_stock_key, -1)
-- 把购买过的用户id存入订单
redis.call("sadd", order_key, user_id)
return 0