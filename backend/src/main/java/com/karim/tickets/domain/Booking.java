package com.karim.tickets.domain;

import com.karim.tickets.domain.Enums.BookingStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings")
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false) private Long eventId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private BookingStatus status = BookingStatus.PENDING_PAYMENT;
    @Column(nullable = false) private int amountCents;
    @Column(nullable = false) private String holdToken;
    @Column(nullable = false) private Instant holdExpiresAt;
    private String paymentOrderId;
    private String paymentId;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    @ManyToMany
    @JoinTable(name = "booking_seats", joinColumns = @JoinColumn(name = "booking_id"),
            inverseJoinColumns = @JoinColumn(name = "seat_id"))
    @OrderBy("section ASC, row ASC, number ASC")
    private List<Seat> seats = new ArrayList<>();

    protected Booking() {}

    public Booking(Long userId, Long eventId, List<Seat> seats, String holdToken, Instant holdExpiresAt) {
        this.userId = userId;
        this.eventId = eventId;
        this.seats = new ArrayList<>(seats);
        this.holdToken = holdToken;
        this.holdExpiresAt = holdExpiresAt;
        this.amountCents = seats.stream().mapToInt(Seat::getPriceCents).sum();
    }

    public void transition(BookingStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public boolean isExpired(Instant now) { return now.isAfter(holdExpiresAt); }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public BookingStatus getStatus() { return status; }
    public int getAmountCents() { return amountCents; }
    public String getHoldToken() { return holdToken; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
    public String getPaymentOrderId() { return paymentOrderId; }
    public void setPaymentOrderId(String id) { this.paymentOrderId = id; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String id) { this.paymentId = id; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Seat> getSeats() { return seats; }
}
