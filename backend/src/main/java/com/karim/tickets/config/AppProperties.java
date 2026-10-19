package com.karim.tickets.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank @Size(min = 32, message = "JWT_SECRET must be at least 32 characters") String jwtSecret,
        Duration jwtTtl,
        Duration holdTtl,
        int maxSeatsPerBooking,
        String frontendUrl,
        Payment payment,
        Seed seed) {

    public record Payment(String provider, @NotBlank String webhookSecret, String currency,
                          String razorpayKeyId, String razorpayKeySecret) {}

    public record Seed(boolean enabled, String adminPassword, String userPassword) {}
}
