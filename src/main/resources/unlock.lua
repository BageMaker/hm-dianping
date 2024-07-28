-- 比较线程标识中与锁中的线程标识是否相同
if(redis.call('get',KEYS[1]) == ARGV[1]) then
    -- 相同则释放锁
    return redis.call('del', KEYS[1])
end
return 0