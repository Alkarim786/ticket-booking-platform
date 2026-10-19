package com.karim.tickets.service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Distributed seat locks in Redis. A hold grabs all requested seats or none, atomically,
 * in one Lua script, so two app instances can never split a multi-seat request between them.
 * Keys share the {eventId} hash tag, so on Redis Cluster all seats of one event live in one slot
 * (multi-key scripts require that).
 */
@Service
public class SeatLockService {

    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            for _, k in ipairs(KEYS) do
              local owner = redis.call('GET', k)
              if owner and owner ~= ARGV[1] then return 0 end
            end
            for _, k in ipairs(KEYS) do
              redis.call('SET', k, ARGV[1], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local n = 0
            for _, k in ipairs(KEYS) do
              if redis.call('GET', k) == ARGV[1] then
                redis.call('DEL', k)
                n = n + 1
              end
            end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;

    public SeatLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static List<String> keys(long eventId, Collection<Long> seatIds) {
        return seatIds.stream().sorted().map(id -> "seat:{" + eventId + "}:" + id).toList();
    }

    /** True if every seat was locked for {@code token}; false (and nothing locked) otherwise. */
    public boolean acquire(long eventId, Collection<Long> seatIds, String token, Duration ttl) {
        Long ok = redis.execute(ACQUIRE, keys(eventId, seatIds), token, String.valueOf(ttl.toMillis()));
        return ok != null && ok == 1L;
    }

    /** Releases only the locks still owned by {@code token}; returns how many were released. */
    public long release(long eventId, Collection<Long> seatIds, String token) {
        Long n = redis.execute(RELEASE, keys(eventId, seatIds), token);
        return n == null ? 0 : n;
    }

    public String owner(long eventId, long seatId) {
        return redis.opsForValue().get("seat:{" + eventId + "}:" + seatId);
    }
}
