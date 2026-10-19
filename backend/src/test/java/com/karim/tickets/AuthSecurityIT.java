package com.karim.tickets;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthSecurityIT extends IntegrationTest {

    @Test
    void registerLoginAndMe() throws Exception {
        var reg = postJson("/api/auth/register", null, Map.of("email", "Karim@Example.com", "password", "s3cret-pass", "name", "Karim"))
                .andExpect(status().isCreated());
        String token = body(reg).get("token").asText();
        assertThat(body(reg).at("/user/email").asText()).isEqualTo("karim@example.com");

        getAuth("/api/auth/me", "Bearer " + token).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("USER"));
        postJson("/api/auth/login", null, Map.of("email", "karim@example.com", "password", "s3cret-pass")).andExpect(status().isOk());
        postJson("/api/auth/register", null, Map.of("email", "karim@example.com", "password", "another-pass", "name", "K"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
        postJson("/api/auth/register", null, Map.of("email", "x@example.com", "password", "right-password", "name", "X"));
        var a = body(postJson("/api/auth/login", null, Map.of("email", "x@example.com", "password", "wrong-password"))
                .andExpect(status().isUnauthorized()));
        var b = body(postJson("/api/auth/login", null, Map.of("email", "nobody@example.com", "password", "whatever1"))
                .andExpect(status().isUnauthorized()));
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
    }

    @Test
    void validationErrors() throws Exception {
        postJson("/api/auth/register", null, Map.of("email", "not-an-email", "password", "short", "name", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void authorization() throws Exception {
        getAuth("/api/bookings", null).andExpect(status().isUnauthorized());
        getAuth("/api/bookings", "Bearer not.a.jwt").andExpect(status().isUnauthorized());
        getAuth("/api/events", null).andExpect(status().isOk()); // public
        var event = Map.of("name", "E", "venue", "V", "startsAt", Instant.now().plusSeconds(86400).toString(),
                "sections", List.of(Map.of("name", "A", "rows", 1, "seatsPerRow", 2, "priceCents", 100)));
        postJson("/api/admin/events", userToken(), event).andExpect(status().isForbidden());
        postJson("/api/admin/events", adminToken(), event).andExpect(status().isCreated());
    }
}
