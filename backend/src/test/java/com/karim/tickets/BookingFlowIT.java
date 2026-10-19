package com.karim.tickets;

import com.karim.tickets.payment.Payments.WebhookSigner;
import com.karim.tickets.repo.Repos.SeatReservationRepository;
import com.karim.tickets.service.BookingService;
import com.karim.tickets.service.SeatLockService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingFlowIT extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired SeatReservationRepository reservations;
    @Autowired SeatLockService locks;
    @Autowired WebhookSigner signer;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String seatStatus(long eventId, long seatId) throws Exception {
        for (var s : body(getAuth("/api/events/" + eventId + "/seats", null))) {
            if (s.get("id").asLong() == seatId) {
                return s.get("status").asText();
            }
        }
        throw new AssertionError("seat not found");
    }

    @Test
    void holdPayConfirmEndToEnd() throws Exception {
        long event = createEvent(2, 5);
        List<Long> seats = seatIds(event);
        String alice = userToken();
        var hold = body(postJson("/api/bookings/hold", alice, Map.of("eventId", event, "seatIds", seats.subList(0, 2)))
                .andExpect(status().isCreated()));
        long booking = hold.get("id").asLong();
        assertThat(hold.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(hold.get("amountCents").asInt()).isEqualTo(100000);
        assertThat(seatStatus(event, seats.get(0))).isEqualTo("HELD");

        // Someone else can't take an overlapping seat.
        postJson("/api/bookings/hold", userToken(), Map.of("eventId", event, "seatIds", List.of(seats.get(1), seats.get(2))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"));
        assertThat(seatStatus(event, seats.get(2))).isEqualTo("AVAILABLE"); // all-or-nothing: seat 2 not left locked

        var order = body(postJson("/api/payments/" + booking + "/checkout", alice, Map.of()).andExpect(status().isOk()));
        assertThat(order.get("orderId").asText()).startsWith("order_");
        postJson("/api/payments/" + booking + "/simulate", alice, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("CONFIRMED"));

        getAuth("/api/bookings/" + booking, alice).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(seatStatus(event, seats.get(0))).isEqualTo("BOOKED");
        assertThat(reservations.countByBookingId(booking)).isEqualTo(2);
        assertThat(locks.owner(event, seats.get(0))).isNull(); // Redis locks released after commit
        // Another user can't see Alice's booking.
        getAuth("/api/bookings/" + booking, userToken()).andExpect(status().isNotFound());
    }

    @Test
    void webhookSignatureAndIdempotency() throws Exception {
        long event = createEvent(1, 3);
        List<Long> seats = seatIds(event);
        String bob = userToken();
        long booking = body(postJson("/api/bookings/hold", bob, Map.of("eventId", event, "seatIds", List.of(seats.get(0))))).get("id").asLong();
        String orderId = body(postJson("/api/payments/" + booking + "/checkout", bob, Map.of())).get("orderId").asText();
        String webhook = json.writeValueAsString(Map.of("id", "evt_1_" + booking, "event", "payment.captured",
                "payload", Map.of("payment", Map.of("id", "pay_" + booking, "order_id", orderId, "amount", 50000))));

        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(webhook)
                .header("X-Razorpay-Signature", "deadbeef")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(webhook)
                .header("X-Razorpay-Signature", signer.sign(webhook))).andExpect(jsonPath("$.result").value("CONFIRMED"));
        // The provider retries the same event: processed once.
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(webhook)
                .header("X-Razorpay-Signature", signer.sign(webhook))).andExpect(jsonPath("$.result").value("DUPLICATE"));
        // A different event id for an already-confirmed order is also harmless.
        String again = webhook.replace("evt_1_", "evt_2_");
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(again)
                .header("X-Razorpay-Signature", signer.sign(again))).andExpect(jsonPath("$.result").value("ALREADY_CONFIRMED"));
    }

    @Test
    void unpaidHoldsExpireAndLatePaymentNeedsRefund() throws Exception {
        long event = createEvent(1, 4);
        List<Long> seats = seatIds(event);
        String carol = userToken();
        long booking = body(postJson("/api/bookings/hold", carol, Map.of("eventId", event, "seatIds", seats.subList(0, 2)))).get("id").asLong();
        postJson("/api/payments/" + booking + "/checkout", carol, Map.of());

        clock.advance(Duration.ofMinutes(6)); // hold TTL is 5 minutes
        assertThat(bookingService.expireHolds()).isGreaterThanOrEqualTo(1);
        getAuth("/api/bookings/" + booking, carol).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(seatStatus(event, seats.get(0))).isEqualTo("AVAILABLE");

        // The seats can be held again by someone else...
        postJson("/api/bookings/hold", userToken(), Map.of("eventId", event, "seatIds", seats.subList(0, 2))).andExpect(status().isCreated());
        // ...and Carol's late payment is flagged for refund instead of double-booking.
        postJson("/api/payments/" + booking + "/simulate", carol, Map.of()).andExpect(status().isConflict());
    }

    @Test
    void lateWebhookAfterExpiryBecomesRefundRequired() throws Exception {
        long event = createEvent(1, 2);
        List<Long> seats = seatIds(event);
        String dan = userToken();
        long booking = body(postJson("/api/bookings/hold", dan, Map.of("eventId", event, "seatIds", List.of(seats.get(0))))).get("id").asLong();
        String orderId = body(postJson("/api/payments/" + booking + "/checkout", dan, Map.of())).get("orderId").asText();
        clock.advance(Duration.ofMinutes(6)); // expired, but the expiry job hasn't run yet
        String webhook = json.writeValueAsString(Map.of("id", "evt_late_" + booking, "event", "payment.captured",
                "payload", Map.of("payment", Map.of("id", "pay_late_" + booking, "order_id", orderId, "amount", 50000))));
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(webhook)
                .header("X-Razorpay-Signature", signer.sign(webhook))).andExpect(jsonPath("$.result").value("REFUND_REQUIRED"));
        assertThat(seatStatus(event, seats.get(0))).isNotEqualTo("BOOKED");
    }

    @Test
    void cancelReleasesSeatsAndValidation() throws Exception {
        long event = createEvent(1, 3);
        List<Long> seats = seatIds(event);
        String erin = userToken();
        long booking = body(postJson("/api/bookings/hold", erin, Map.of("eventId", event, "seatIds", List.of(seats.get(0))))).get("id").asLong();
        postJson("/api/bookings/" + booking + "/cancel", erin, Map.of()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(seatStatus(event, seats.get(0))).isEqualTo("AVAILABLE");
        postJson("/api/bookings/" + booking + "/cancel", erin, Map.of()).andExpect(status().isConflict());
        postJson("/api/bookings/hold", erin, Map.of("eventId", event, "seatIds", List.of())).andExpect(status().isBadRequest());
        postJson("/api/bookings/hold", erin, Map.of("eventId", event, "seatIds", List.of(999_999L))).andExpect(status().isNotFound());
    }
}
