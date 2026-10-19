package com.karim.tickets.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** A processed payment-provider webhook event; its id is the idempotency key. */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {
    @Id private String eventId;
    private Long bookingId;
    @Column(nullable = false) private String outcome;
    @Column(nullable = false) private Instant receivedAt = Instant.now();

    protected PaymentEvent() {}

    public PaymentEvent(String eventId, Long bookingId, String outcome) {
        this.eventId = eventId;
        this.bookingId = bookingId;
        this.outcome = outcome;
    }

    public String getOutcome() { return outcome; }
}
