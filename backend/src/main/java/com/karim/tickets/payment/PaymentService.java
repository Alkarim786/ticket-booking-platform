package com.karim.tickets.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karim.tickets.domain.Booking;
import com.karim.tickets.domain.Enums.BookingStatus;
import com.karim.tickets.domain.PaymentEvent;
import com.karim.tickets.payment.Payments.PaymentGateway;
import com.karim.tickets.payment.Payments.PaymentOrder;
import com.karim.tickets.payment.Payments.WebhookSigner;
import com.karim.tickets.repo.Repos.BookingRepository;
import com.karim.tickets.repo.Repos.PaymentEventRepository;
import com.karim.tickets.service.ApiException;
import com.karim.tickets.service.BookingService;
import com.karim.tickets.service.BookingService.ConfirmOutcome;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private final PaymentGateway gateway;
    private final WebhookSigner signer;
    private final BookingRepository bookings;
    private final PaymentEventRepository events;
    private final BookingService bookingService;
    private final ObjectMapper json;

    public PaymentService(PaymentGateway gateway, WebhookSigner signer, BookingRepository bookings,
                          PaymentEventRepository events, BookingService bookingService, ObjectMapper json) {
        this.gateway = gateway;
        this.signer = signer;
        this.bookings = bookings;
        this.events = events;
        this.bookingService = bookingService;
        this.json = json;
    }

    /** Create (or return the existing) payment order for an unpaid hold. */
    public PaymentOrder checkout(long userId, long bookingId) {
        Booking b = bookingService.get(userId, bookingId);
        if (b.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("NOT_PAYABLE", "booking is " + b.getStatus());
        }
        if (b.getPaymentOrderId() == null) {
            PaymentOrder order = gateway.createOrder(b);
            b.setPaymentOrderId(order.orderId());
            bookings.save(b);
            return order;
        }
        return new PaymentOrder(gateway.name(), b.getPaymentOrderId(), b.getAmountCents(), "INR", null);
    }

    /**
     * Webhook entry point. Verifies the signature over the raw body, then processes each
     * provider event id at most once (insert into payment_events is the idempotency check).
     */
    public String handleWebhook(String rawBody, String signature) {
        if (!signer.verify(rawBody, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "webhook signature mismatch");
        }
        JsonNode body;
        try {
            body = json.readTree(rawBody);
        } catch (IOException e) {
            throw ApiException.bad("BAD_PAYLOAD", "webhook body is not JSON");
        }
        String eventId = body.path("id").asText();
        if (eventId.isBlank()) {
            throw ApiException.bad("BAD_PAYLOAD", "missing event id");
        }
        if (events.existsById(eventId)) {
            return "DUPLICATE";
        }
        String outcome = "IGNORED";
        Long bookingId = null;
        if ("payment.captured".equals(body.path("event").asText())) {
            JsonNode p = body.path("payload").path("payment");
            String orderId = p.path("order_id").asText();
            ConfirmOutcome res = bookingService.confirm(orderId, p.path("id").asText(), p.path("amount").asLong());
            outcome = res.name();
            bookingId = bookings.findByPaymentOrderId(orderId).map(Booking::getId).orElse(null);
        }
        try {
            events.save(new PaymentEvent(eventId, bookingId, outcome));
        } catch (DataIntegrityViolationException e) {
            return "DUPLICATE"; // a concurrent delivery of the same event won the race
        }
        return outcome;
    }

    /** Mock provider only: behave like the customer paid, by sending a signed webhook. */
    public String simulateCapture(long userId, long bookingId) {
        if (!"mock".equals(gateway.name())) {
            throw ApiException.bad("NOT_TEST_MODE", "simulation is only available with the mock gateway");
        }
        PaymentOrder order = checkout(userId, bookingId);
        Booking b = bookingService.get(userId, bookingId);
        try {
            String body = json.writeValueAsString(Map.of(
                    "id", "evt_" + UUID.randomUUID(),
                    "event", "payment.captured",
                    "payload", Map.of("payment", Map.of(
                            "id", "pay_" + UUID.randomUUID().toString().replace("-", ""),
                            "order_id", order.orderId(),
                            "amount", b.getAmountCents()))));
            return handleWebhook(body, signer.sign(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
