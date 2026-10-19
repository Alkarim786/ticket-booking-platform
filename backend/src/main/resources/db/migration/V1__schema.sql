CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100),
    name          VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    provider      VARCHAR(20)  NOT NULL DEFAULT 'LOCAL',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    venue       VARCHAR(200) NOT NULL,
    starts_at   TIMESTAMPTZ  NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    section     VARCHAR(30) NOT NULL,
    row_label   VARCHAR(5)  NOT NULL,
    number      INT         NOT NULL,
    price_cents INT         NOT NULL CHECK (price_cents >= 0),
    status      VARCHAR(20) NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    UNIQUE (event_id, section, row_label, number)
);
CREATE INDEX idx_seats_event ON seats (event_id);

CREATE TABLE bookings (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id),
    event_id         BIGINT      NOT NULL REFERENCES events (id),
    status           VARCHAR(30) NOT NULL,
    amount_cents     INT         NOT NULL,
    hold_token       VARCHAR(64) NOT NULL,
    hold_expires_at  TIMESTAMPTZ NOT NULL,
    payment_order_id VARCHAR(100) UNIQUE,
    payment_id       VARCHAR(100) UNIQUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_bookings_expiry ON bookings (status, hold_expires_at);
CREATE INDEX idx_bookings_user ON bookings (user_id, created_at DESC);

CREATE TABLE booking_seats (
    booking_id BIGINT NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    seat_id    BIGINT NOT NULL REFERENCES seats (id),
    PRIMARY KEY (booking_id, seat_id)
);

-- Last line of defence against double booking: one confirmed booking per seat, enforced by the PK.
CREATE TABLE seat_reservations (
    seat_id    BIGINT PRIMARY KEY REFERENCES seats (id),
    booking_id BIGINT      NOT NULL REFERENCES bookings (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Webhook idempotency: each provider event id is processed once.
CREATE TABLE payment_events (
    event_id    VARCHAR(100) PRIMARY KEY,
    booking_id  BIGINT,
    outcome     VARCHAR(30) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
