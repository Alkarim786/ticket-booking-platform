package com.karim.tickets.web;

import com.karim.tickets.domain.Booking;
import com.karim.tickets.domain.Event;
import com.karim.tickets.domain.Seat;
import com.karim.tickets.domain.User;
import com.karim.tickets.service.EventService.SectionSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request and response bodies. Entities never leave the service layer. */
public final class Dtos {
    private Dtos() {}

    public record RegisterRequest(@Email @NotBlank String email,
                                  @NotBlank @Size(min = 8, max = 100) String password,
                                  @NotBlank @Size(max = 100) String name) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record UserView(long id, String email, String name, String role) {
        static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getName(), u.getRole().name());
        }
    }

    public record AuthResponse(String token, UserView user) {}

    public record EventView(long id, String name, String venue, Instant startsAt, String description) {
        static EventView of(Event e) {
            return new EventView(e.getId(), e.getName(), e.getVenue(), e.getStartsAt(), e.getDescription());
        }
    }

    public record SeatView(long id, String section, String row, int number, int priceCents, String status) {
        static SeatView of(Seat s) {
            return new SeatView(s.getId(), s.getSection(), s.getRow(), s.getNumber(), s.getPriceCents(), s.getStatus().name());
        }
    }

    public record CreateEventRequest(@NotBlank String name, @NotBlank String venue, @NotNull @Future Instant startsAt,
                                     String description, @NotEmpty List<@Valid SectionRequest> sections) {}

    public record SectionRequest(@NotBlank String name, int rows, int seatsPerRow, int priceCents) {
        SectionSpec spec() {
            return new SectionSpec(name, rows, seatsPerRow, priceCents);
        }
    }

    public record HoldRequest(@NotNull Long eventId, @NotEmpty List<Long> seatIds) {}

    public record BookingView(long id, long eventId, String status, int amountCents, Instant holdExpiresAt,
                              String paymentOrderId, Instant createdAt, List<SeatView> seats) {
        static BookingView of(Booking b) {
            return new BookingView(b.getId(), b.getEventId(), b.getStatus().name(), b.getAmountCents(),
                    b.getHoldExpiresAt(), b.getPaymentOrderId(), b.getCreatedAt(),
                    b.getSeats().stream().map(SeatView::of).toList());
        }
    }
}
