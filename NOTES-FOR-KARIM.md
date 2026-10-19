# Notes for Karim: SeatRush (Java full stack)

## Resume bullets (real measured numbers)
- Built a **full-stack ticket-booking platform** (Spring Boot 3.5, Java 21, PostgreSQL, Redis, React) that prevents double booking with three layers: **atomic Redis Lua seat locks**, **JPA optimistic locking**, and a **DB primary-key constraint**. Verified by **200 concurrent users racing for one seat → exactly 1 winner**.
- Load-tested with **k6: 500 simultaneous users for 100 seats across 2 replicas → 100 confirmed, 0 oversold, 0% failed requests**. Fixed the first run's 25% error rate (Redis timeouts and Tomcat backlog overflow) with virtual threads, timeout tuning, and 503 back-pressure.
- Implemented JWT + Google OAuth2 auth, a hold-then-pay flow with automatic expiry, **HMAC-verified idempotent payment webhooks** (Razorpay-compatible), and a **live seat map over SSE synced across instances with Redis pub/sub**. Covered by 12 Testcontainers integration tests.

## 5 likely interview questions
1. **"How do you stop two people booking the same seat?"**
   - Layer 1: Redis Lua locks all seats or none (atomic, fast, cross-instance).
   - Layer 2: an optimistic `@Version` on the seat row, so of two transactions that read AVAILABLE, only one commits HELD.
   - Layer 3: `seat_reservations` PK at confirmation.
   - Why all three: Redis can fail over and lose a lock; the DB is the source of truth.

2. **"Why not just `SELECT ... FOR UPDATE`?"** It works for one DB, but holds row locks across the request and serialises the rush on the database. Redis rejects losers in about a millisecond before touching Postgres; optimistic locking keeps DB transactions short. Pessimistic locks are fine for low contention; here contention is the whole point.

3. **"What if the payment succeeds after the hold expired?"** The webhook sees `hold_expires_at` has passed and marks the booking `REFUND_REQUIRED` instead of confirming. The seats may now belong to someone else, so you never steal them. Then issue a refund (roadmap).

4. **"How do you make webhooks safe?"**
   - Verify the HMAC over the **raw** body (re-serialising JSON changes bytes) with a constant-time compare.
   - Idempotency: insert the provider event id into `payment_events` (PK). A duplicate means it was already processed.
   - Confirming an already-confirmed booking is a no-op.

5. **"How does the live seat map work with multiple servers?"** After the DB commit, the server publishes `{eventId, seats}` to Redis pub/sub. Every replica is subscribed and pushes to its own SSE clients. Publishing *after commit* matters: publishing inside the transaction could announce a change that then rolls back.

## Understand this before interviews
- **`BookingService.hold`:** the lock is acquired *outside* the transaction and released in every failure path (`catch`), including `OptimisticLockingFailureException`. On success, locks stay until confirm/expire/cancel, which release them `afterCommit`.
- **`SeatLockService` Lua:** loop 1 checks every key (owned by someone else? → return 0); loop 2 sets them all. Redis runs the script atomically, so nobody can sneak in between. The keys share the `{eventId}` hash tag, so Redis Cluster puts them in one slot (multi-key scripts require that).
- **`confirm`:** check order exists → already confirmed? → amount matches? → still pending and not expired? → BOOKED + reservations + CONFIRMED, then `flush()` so a PK violation surfaces inside the transaction.
- **Load-test story:**
  - First k6 run: 25% failures.
  - Logs showed `RedisCommandTimeoutException` (500 ms) and nginx 502s.
  - Causes: a tight client timeout on a CPU-starved box, Tomcat's backlog of 100 overflowing, and the test itself doing BCrypt registration during the rush.
  - Fixes: virtual threads, accept-count, 2 s timeout, 503 Retry-After, registering users in k6 `setup()`.
  - Result: 0% failures. Great "debugging under load" story.
- **SSE gotcha:** `SseEmitter` doesn't send headers until the first write. Send an initial event, and permit `DispatcherType.ASYNC` in Spring Security.
- **Windows-only test quirk:** Java NIO uses AF_UNIX sockets in `%TEMP%` for selectors; a very long TEMP path breaks them (108-char limit). Locally the tests ran with `-Djdk.net.unixdomain.tmpdir=C:/tools/tmp`; CI on Linux needs nothing.
