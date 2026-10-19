package com.karim.tickets.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karim.tickets.domain.Seat;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live seat-map updates. Changes are published to a Redis channel after the DB transaction
 * commits; every app instance subscribes and relays them to its own SSE clients, so a browser
 * connected to instance A sees seats taken through instance B.
 */
@Service
public class SeatUpdates {
    static final String CHANNEL = "seat-updates";
    private static final Logger log = LoggerFactory.getLogger(SeatUpdates.class);

    public record SeatChange(long id, String status) {}

    public record Message(long eventId, List<SeatChange> seats) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SeatUpdates(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** Publish after the surrounding transaction commits (immediately if there is none). */
    public void publish(long eventId, List<Seat> seats) {
        Message msg = new Message(eventId, seats.stream().map(s -> new SeatChange(s.getId(), s.getStatus().name())).toList());
        Runnable send = () -> {
            try {
                redis.convertAndSend(CHANNEL, json.writeValueAsString(msg));
            } catch (JsonProcessingException | RuntimeException e) {
                log.warn("seat update publish failed: {}", e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    public SseEmitter subscribe(long eventId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; heartbeats keep proxies happy
        List<SseEmitter> list = emitters.computeIfAbsent(eventId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            // Send something at once: the response (and its headers) isn't committed until the
            // first write, so without this the browser waits for the first heartbeat (20 s).
            emitter.send(SseEmitter.event().comment("connected").reconnectTime(3000));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public int subscriberCount(long eventId) {
        return emitters.getOrDefault(eventId, List.of()).size();
    }

    void onRedisMessage(String payload) {
        try {
            Message msg = json.readValue(payload, Message.class);
            for (SseEmitter e : emitters.getOrDefault(msg.eventId(), List.of())) {
                try {
                    e.send(SseEmitter.event().name("seats").data(msg.seats()));
                } catch (IOException | IllegalStateException ex) {
                    e.completeWithError(ex);
                }
            }
        } catch (IOException ex) {
            log.warn("bad seat update payload: {}", ex.getMessage());
        }
    }

    /** Called every 20s so idle SSE connections aren't dropped by proxies. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 20_000)
    void heartbeat() {
        emitters.values().forEach(list -> list.forEach(e -> {
            try {
                e.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException ex) {
                e.completeWithError(ex);
            }
        }));
    }

    @Configuration
    static class ListenerConfig {
        @Bean
        RedisMessageListenerContainer seatUpdateListener(RedisConnectionFactory cf, SeatUpdates updates) {
            RedisMessageListenerContainer c = new RedisMessageListenerContainer();
            c.setConnectionFactory(cf);
            c.addMessageListener((message, pattern) -> updates.onRedisMessage(new String(message.getBody())),
                    new ChannelTopic(CHANNEL));
            return c;
        }
    }
}
