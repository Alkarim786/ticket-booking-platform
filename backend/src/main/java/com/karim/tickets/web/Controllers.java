package com.karim.tickets.web;

import com.karim.tickets.payment.PaymentService;
import com.karim.tickets.payment.Payments.PaymentOrder;
import com.karim.tickets.service.AuthService;
import com.karim.tickets.service.BookingService;
import com.karim.tickets.service.EventService;
import com.karim.tickets.service.SeatUpdates;
import com.karim.tickets.web.Dtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** REST controllers. Each is small, so they share one file. */
public final class Controllers {
    private Controllers() {}

    static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @RestController
    @RequestMapping("/api/auth")
    public static class AuthController {
        private final AuthService auth;

        public AuthController(AuthService auth) {
            this.auth = auth;
        }

        @PostMapping("/register")
        @ResponseStatus(HttpStatus.CREATED)
        public AuthResponse register(@Valid @RequestBody RegisterRequest r) {
            var s = auth.register(r.email(), r.password(), r.name());
            return new AuthResponse(s.token(), UserView.of(s.user()));
        }

        @PostMapping("/login")
        public AuthResponse login(@Valid @RequestBody LoginRequest r) {
            var s = auth.login(r.email(), r.password());
            return new AuthResponse(s.token(), UserView.of(s.user()));
        }

        @GetMapping("/me")
        public UserView me(@AuthenticationPrincipal Jwt jwt) {
            return UserView.of(auth.get(userId(jwt)));
        }
    }

    @RestController
    @RequestMapping("/api")
    public static class EventController {
        private final EventService events;
        private final SeatUpdates updates;

        public EventController(EventService events, SeatUpdates updates) {
            this.events = events;
            this.updates = updates;
        }

        @GetMapping("/events")
        public List<EventView> list() {
            return events.list().stream().map(EventView::of).toList();
        }

        @GetMapping("/events/{id}")
        public EventView get(@PathVariable long id) {
            return EventView.of(events.get(id));
        }

        @GetMapping("/events/{id}/seats")
        public List<SeatView> seats(@PathVariable long id) {
            return events.seats(id).stream().map(SeatView::of).toList();
        }

        /** Live seat-map updates (Server-Sent Events). Seat status is public, so no auth is needed. */
        @GetMapping(path = "/events/{id}/seats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter stream(@PathVariable long id) {
            events.get(id);
            return updates.subscribe(id);
        }

        @PostMapping("/admin/events")
        @ResponseStatus(HttpStatus.CREATED)
        public EventView create(@Valid @RequestBody CreateEventRequest r) {
            return EventView.of(events.create(r.name(), r.venue(), r.startsAt(), r.description(),
                    r.sections().stream().map(SectionRequest::spec).toList()));
        }
    }

    @RestController
    @RequestMapping("/api/bookings")
    public static class BookingController {
        private final BookingService bookings;

        public BookingController(BookingService bookings) {
            this.bookings = bookings;
        }

        @PostMapping("/hold")
        @ResponseStatus(HttpStatus.CREATED)
        public BookingView hold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest r) {
            return BookingView.of(bookings.hold(userId(jwt), r.eventId(), r.seatIds()));
        }

        @GetMapping
        public List<BookingView> mine(@AuthenticationPrincipal Jwt jwt) {
            return bookings.mine(userId(jwt)).stream().map(BookingView::of).toList();
        }

        @GetMapping("/{id}")
        public BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
            return BookingView.of(bookings.get(userId(jwt), id));
        }

        @PostMapping("/{id}/cancel")
        public BookingView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
            return BookingView.of(bookings.cancel(userId(jwt), id));
        }
    }

    @RestController
    @RequestMapping("/api/payments")
    public static class PaymentController {
        private final PaymentService payments;

        public PaymentController(PaymentService payments) {
            this.payments = payments;
        }

        @PostMapping("/{bookingId}/checkout")
        public PaymentOrder checkout(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
            return payments.checkout(userId(jwt), bookingId);
        }

        /** Provider webhook: the raw body is needed byte for byte to verify the HMAC signature. */
        @PostMapping("/webhook")
        public Map<String, String> webhook(@RequestBody String rawBody,
                                           @RequestHeader(value = "X-Razorpay-Signature", required = false) String sig) {
            return Map.of("result", payments.handleWebhook(rawBody, sig));
        }

        @PostMapping("/{bookingId}/simulate")
        public Map<String, String> simulate(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
            return Map.of("result", payments.simulateCapture(userId(jwt), bookingId));
        }
    }
}
