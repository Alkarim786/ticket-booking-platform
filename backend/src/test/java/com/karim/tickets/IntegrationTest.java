package com.karim.tickets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karim.tickets.domain.Enums.Role;
import com.karim.tickets.domain.User;
import com.karim.tickets.repo.Repos.UserRepository;
import com.karim.tickets.security.JwtService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base class: one real PostgreSQL and one real Redis (Testcontainers) shared by all tests,
 * plus a controllable clock for testing hold expiry.
 */
@SpringBootTest(properties = {"app.expiry-interval=1h"})
@AutoConfigureMockMvc
@Import(IntegrationTest.ClockConfig.class)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("app.jwt-secret", () -> "test-secret-test-secret-test-secret-123456");
        r.add("app.payment.webhook-secret", () -> "whsec_test_123");
    }

    /** A clock tests can move forward. */
    public static class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        public void advance(Duration d) { now = now.plus(d); }
        public void reset() { now = Instant.now(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected UserRepository users;
    @Autowired protected JwtService jwt;
    @Autowired protected MutableClock clock;

    protected String userToken() {
        User u = users.save(new User("u-" + UUID.randomUUID() + "@test.local", null, "Test User", Role.USER, "LOCAL"));
        return "Bearer " + jwt.issue(u);
    }

    protected String adminToken() {
        User u = users.save(new User("a-" + UUID.randomUUID() + "@test.local", null, "Admin", Role.ADMIN, "LOCAL"));
        return "Bearer " + jwt.issue(u);
    }

    protected ResultActions postJson(String url, String token, Object body) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) {
            req.header("Authorization", token);
        }
        return mvc.perform(req);
    }

    protected ResultActions getAuth(String url, String token) throws Exception {
        var req = get(url);
        if (token != null) {
            req.header("Authorization", token);
        }
        return mvc.perform(req);
    }

    protected JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** Creates an event with one section of rows x perRow seats; returns the event id. */
    protected long createEvent(int rows, int perRow) throws Exception {
        var req = java.util.Map.of(
                "name", "Test Event " + UUID.randomUUID(), "venue", "Test Arena",
                "startsAt", Instant.now().plus(Duration.ofDays(7)).toString(),
                "sections", java.util.List.of(java.util.Map.of("name", "A", "rows", rows, "seatsPerRow", perRow, "priceCents", 50000)));
        return body(postJson("/api/admin/events", adminToken(), req)).get("id").asLong();
    }

    protected java.util.List<Long> seatIds(long eventId) throws Exception {
        JsonNode seats = body(getAuth("/api/events/" + eventId + "/seats", null));
        java.util.List<Long> ids = new java.util.ArrayList<>();
        seats.forEach(s -> ids.add(s.get("id").asLong()));
        return ids;
    }
}
