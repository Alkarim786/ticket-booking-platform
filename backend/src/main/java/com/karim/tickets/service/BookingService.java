package com.karim.tickets.service;

import com.karim.tickets.config.AppProperties;
import com.karim.tickets.domain.Booking;
import com.karim.tickets.domain.Enums.BookingStatus;
import com.karim.tickets.domain.Enums.SeatStatus;
import com.karim.tickets.domain.Seat;
import com.karim.tickets.domain.SeatReservation;
import com.karim.tickets.repo.Repos.BookingRepository;
import com.karim.tickets.repo.Repos.SeatRepository;
import com.karim.tickets.repo.Repos.SeatReservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seat holds and confirmations. Three independent layers stop double booking:
 * (1) all-or-nothing Redis locks, (2) optimistic locking on seats in the DB,
 * (3) the seat_reservations primary key at confirmation time.
 */
@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    /** Redis locks outlive the DB hold slightly; the DB is the source of truth for expiry. */
    private static final Duration LOCK_GRACE = Duration.ofSeconds(30);

    public enum ConfirmOutcome { CONFIRMED, ALREADY_CONFIRMED, UNKNOWN_ORDER, REFUND_REQUIRED, AMOUNT_MISMATCH }

    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final SeatReservationRepository reservations;
    private final SeatLockService locks;
    private final SeatUpdates updates;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final Clock clock;

    public BookingService(SeatRepository seats, BookingRepository bookings, SeatReservationRepository reservations,
                          SeatLockService locks, SeatUpdates updates, PlatformTransactionManager txm,
                          AppProperties props, Clock clock) {
        this.seats = seats;
        this.bookings = bookings;
        this.reservations = reservations;
        this.locks = locks;
        this.updates = updates;
        this.tx = new TransactionTemplate(txm);
        this.props = props;
        this.clock = clock;
    }

    /** Hold seats for a user for {@code app.hold-ttl}; payment must arrive before it expires. */
    public Booking hold(long userId, long eventId, Collection<Long> requested) {
        Set<Long> ids = new LinkedHashSet<>(requested);
        if (ids.isEmpty() || ids.size() > props.maxSeatsPerBooking()) {
            throw ApiException.bad("INVALID_SEAT_COUNT", "select 1-" + props.maxSeatsPerBooking() + " seats");
        }
        String token = UUID.randomUUID().toString();
        if (!locks.acquire(eventId, ids, token, props.holdTtl().plus(LOCK_GRACE))) {
            throw ApiException.conflict("SEATS_UNAVAILABLE", "one or more seats are already being booked");
        }
        try {
            return tx.execute(st -> {
                List<Seat> found = seats.findByIdInAndEventId(ids, eventId);
                if (found.size() != ids.size()) {
                    throw ApiException.notFound("seat");
                }
                for (Seat s : found) {
                    if (s.getStatus() != SeatStatus.AVAILABLE) {
                        throw ApiException.conflict("SEATS_UNAVAILABLE", "seat " + s.getId() + " is " + s.getStatus());
                    }
                    s.setStatus(SeatStatus.HELD);
                }
                seats.saveAllAndFlush(found); // optimistic-lock check happens here
                Booking b = bookings.save(new Booking(userId, eventId, found, token,
                        Instant.now(clock).plus(props.holdTtl())));
                updates.publish(eventId, found);
                return b;
            });
        } catch (OptimisticLockingFailureException e) {
            locks.release(eventId, ids, token);
            throw ApiException.conflict("SEATS_UNAVAILABLE", "seats were taken concurrently");
        } catch (RuntimeException e) {
            locks.release(eventId, ids, token);
            throw e;
        }
    }

    /** Called by the payment webhook. Idempotent; safe to call twice for the same order. */
    public ConfirmOutcome confirm(String orderId, String paymentId, long amountCents) {
        try {
            return tx.execute(st -> {
                Booking b = bookings.findByPaymentOrderId(orderId).orElse(null);
                if (b == null) {
                    return ConfirmOutcome.UNKNOWN_ORDER;
                }
                if (b.getStatus() == BookingStatus.CONFIRMED) {
                    return ConfirmOutcome.ALREADY_CONFIRMED;
                }
                b.setPaymentId(paymentId);
                if (amountCents != b.getAmountCents()) {
                    b.transition(BookingStatus.REFUND_REQUIRED);
                    log.warn("booking {} paid {} but owes {}", b.getId(), amountCents, b.getAmountCents());
                    return ConfirmOutcome.AMOUNT_MISMATCH;
                }
                if (b.getStatus() != BookingStatus.PENDING_PAYMENT || b.isExpired(Instant.now(clock))) {
                    // Money arrived after the hold lapsed; the seats may be someone else's now.
                    b.transition(BookingStatus.REFUND_REQUIRED);
                    return ConfirmOutcome.REFUND_REQUIRED;
                }
                for (Seat s : b.getSeats()) {
                    s.setStatus(SeatStatus.BOOKED);
                    reservations.save(new SeatReservation(s.getId(), b.getId()));
                }
                b.transition(BookingStatus.CONFIRMED);
                reservations.flush(); // PK violation here means a second confirmation: roll back
                updates.publish(b.getEventId(), b.getSeats());
                releaseAfterCommit(b);
                return ConfirmOutcome.CONFIRMED;
            });
        } catch (DataIntegrityViolationException e) {
            log.error("double confirmation blocked by seat_reservations PK for order {}", orderId);
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RESERVED", "seat already reserved");
        }
    }

    /** Release holds whose payment window has passed. Returns how many bookings expired. */
    public int expireHolds() {
        List<Booking> due = bookings.findTop200ByStatusAndHoldExpiresAtBefore(BookingStatus.PENDING_PAYMENT, Instant.now(clock));
        int n = 0;
        for (Booking candidate : due) {
            try {
                Boolean expired = tx.execute(st -> {
                    Booking b = bookings.findWithSeatsById(candidate.getId()).orElseThrow();
                    if (b.getStatus() != BookingStatus.PENDING_PAYMENT) {
                        return false; // confirmed or cancelled meanwhile
                    }
                    freeSeats(b, BookingStatus.EXPIRED);
                    return true;
                });
                if (Boolean.TRUE.equals(expired)) {
                    n++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.debug("booking {} changed while expiring; skipping", candidate.getId());
            }
        }
        return n;
    }

    public Booking cancel(long userId, long bookingId) {
        return tx.execute(st -> {
            Booking b = bookings.findWithSeatsById(bookingId).orElseThrow(() -> ApiException.notFound("booking"));
            if (b.getUserId() != userId) {
                throw ApiException.notFound("booking"); // don't reveal other users' bookings
            }
            if (b.getStatus() != BookingStatus.PENDING_PAYMENT) {
                throw ApiException.conflict("NOT_CANCELLABLE", "only unpaid holds can be cancelled");
            }
            freeSeats(b, BookingStatus.CANCELLED);
            return b;
        });
    }

    public Booking get(long userId, long bookingId) {
        Booking b = bookings.findWithSeatsById(bookingId).orElseThrow(() -> ApiException.notFound("booking"));
        if (b.getUserId() != userId) {
            throw ApiException.notFound("booking");
        }
        return b;
    }

    public List<Booking> mine(long userId) {
        return bookings.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private void freeSeats(Booking b, BookingStatus next) {
        for (Seat s : b.getSeats()) {
            if (s.getStatus() == SeatStatus.HELD) {
                s.setStatus(SeatStatus.AVAILABLE);
            }
        }
        b.transition(next);
        updates.publish(b.getEventId(), b.getSeats());
        releaseAfterCommit(b);
    }

    private void releaseAfterCommit(Booking b) {
        List<Long> ids = b.getSeats().stream().map(Seat::getId).toList();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                locks.release(b.getEventId(), ids, b.getHoldToken());
            }
        });
    }
}
