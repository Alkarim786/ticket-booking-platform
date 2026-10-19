package com.karim.tickets.repo;

import com.karim.tickets.domain.Booking;
import com.karim.tickets.domain.Enums.BookingStatus;
import com.karim.tickets.domain.Enums.SeatStatus;
import com.karim.tickets.domain.Event;
import com.karim.tickets.domain.PaymentEvent;
import com.karim.tickets.domain.Seat;
import com.karim.tickets.domain.SeatReservation;
import com.karim.tickets.domain.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repositories, grouped in one file because each is a few lines. */
public final class Repos {
    private Repos() {}

    public interface UserRepository extends JpaRepository<User, Long> {
        Optional<User> findByEmailIgnoreCase(String email);
        boolean existsByEmailIgnoreCase(String email);
    }

    public interface EventRepository extends JpaRepository<Event, Long> {
        List<Event> findAllByOrderByStartsAtAsc();
    }

    public interface SeatRepository extends JpaRepository<Seat, Long> {
        List<Seat> findByEventIdOrderBySectionAscRowAscNumberAsc(Long eventId);
        List<Seat> findByIdInAndEventId(Collection<Long> ids, Long eventId);
        long countByEventIdAndStatus(Long eventId, SeatStatus status);
    }

    public interface BookingRepository extends JpaRepository<Booking, Long> {
        @EntityGraph(attributePaths = "seats")
        List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);

        @EntityGraph(attributePaths = "seats")
        Optional<Booking> findWithSeatsById(Long id);

        Optional<Booking> findByPaymentOrderId(String orderId);

        List<Booking> findTop200ByStatusAndHoldExpiresAtBefore(BookingStatus status, Instant before);

        long countByEventIdAndStatus(Long eventId, BookingStatus status);
    }

    public interface SeatReservationRepository extends JpaRepository<SeatReservation, Long> {
        long countByBookingId(Long bookingId);
    }

    public interface PaymentEventRepository extends JpaRepository<PaymentEvent, String> {}
}
