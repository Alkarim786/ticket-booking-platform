package com.karim.tickets.domain;

/** Namespace for the small domain enums. */
public final class Enums {
    private Enums() {}

    public enum Role { USER, ADMIN }

    public enum SeatStatus { AVAILABLE, HELD, BOOKED }

    /**
     * PENDING_PAYMENT to CONFIRMED, EXPIRED or CANCELLED. A payment that lands after the
     * hold expired becomes REFUND_REQUIRED (the seats may already belong to someone else).
     */
    public enum BookingStatus { PENDING_PAYMENT, CONFIRMED, EXPIRED, CANCELLED, REFUND_REQUIRED }
}
