package com.karim.tickets.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** One row per confirmed seat. The primary key on seat_id makes a second confirmation impossible. */
@Entity
@Table(name = "seat_reservations")
public class SeatReservation {
    @Id private Long seatId;
    @Column(nullable = false) private Long bookingId;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    protected SeatReservation() {}

    public SeatReservation(Long seatId, Long bookingId) {
        this.seatId = seatId;
        this.bookingId = bookingId;
    }

    public Long getSeatId() { return seatId; }
    public Long getBookingId() { return bookingId; }
}
