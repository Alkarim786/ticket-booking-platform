package com.karim.tickets.payment;

import com.karim.tickets.config.AppProperties;
import com.karim.tickets.domain.Booking;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Payment gateway abstraction plus the two implementations and webhook signing. */
public final class Payments {
    private Payments() {}

    /** What the browser needs to open the provider's checkout. */
    public record PaymentOrder(String provider, String orderId, int amountCents, String currency, String keyId) {}

    public interface PaymentGateway {
        PaymentOrder createOrder(Booking booking);
        String name();
    }

    /** HMAC-SHA256 over the raw request body, hex encoded (the scheme Razorpay webhooks use). */
    @Component
    public static class WebhookSigner {
        private final byte[] secret;

        public WebhookSigner(AppProperties props) {
            this.secret = props.payment().webhookSecret().getBytes(StandardCharsets.UTF_8);
        }

        public String sign(String body) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** Constant-time comparison so the signature can't be guessed byte by byte from timing. */
        public boolean verify(String body, String signature) {
            if (signature == null) {
                return false;
            }
            return MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
        }
    }

    /**
     * Test-mode gateway with no external dependency: orders are local ids, and "paying" means
     * {@code POST /api/payments/{bookingId}/simulate}, which sends a correctly signed webhook
     * through exactly the same code path a real provider's webhook takes.
     */
    @Component
    @ConditionalOnProperty(name = "app.payment.provider", havingValue = "mock", matchIfMissing = true)
    public static class MockGateway implements PaymentGateway {
        private final AppProperties props;

        public MockGateway(AppProperties props) {
            this.props = props;
        }

        @Override
        public PaymentOrder createOrder(Booking b) {
            return new PaymentOrder("mock", "order_" + UUID.randomUUID().toString().replace("-", ""),
                    b.getAmountCents(), props.payment().currency(), "mock_test_key");
        }

        @Override
        public String name() {
            return "mock";
        }
    }

    /** Razorpay test mode: creates a real order via the Orders API when test keys are configured. */
    @Component
    @ConditionalOnProperty(name = "app.payment.provider", havingValue = "razorpay")
    public static class RazorpayGateway implements PaymentGateway {
        private final RestClient http;
        private final AppProperties props;

        public RazorpayGateway(AppProperties props) {
            this.props = props;
            this.http = RestClient.builder()
                    .baseUrl("https://api.razorpay.com/v1")
                    .defaultHeaders(h -> h.setBasicAuth(props.payment().razorpayKeyId(), props.payment().razorpayKeySecret()))
                    .build();
        }

        @Override
        @SuppressWarnings("unchecked")
        public PaymentOrder createOrder(Booking b) {
            Map<String, Object> res = http.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", b.getAmountCents(), "currency", props.payment().currency(),
                            "receipt", "booking_" + b.getId()))
                    .retrieve().body(Map.class);
            return new PaymentOrder("razorpay", String.valueOf(res.get("id")), b.getAmountCents(),
                    props.payment().currency(), props.payment().razorpayKeyId());
        }

        @Override
        public String name() {
            return "razorpay";
        }
    }
}
