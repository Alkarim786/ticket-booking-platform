package com.karim.tickets;

import com.karim.tickets.domain.Booking;
import com.karim.tickets.domain.Enums.Role;
import com.karim.tickets.domain.User;
import com.karim.tickets.service.ApiException;
import com.karim.tickets.service.BookingService;
import com.karim.tickets.service.SeatLockService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Many users racing for the same seats: never more than one holder per seat. */
class ConcurrencyIT extends IntegrationTest {

    @Autowired BookingService bookings;
    @Autowired SeatLockService locks;

    private List<Long> userIds(int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(users.save(new User("c-" + UUID.randomUUID() + "@t.local", null, "U", Role.USER, "LOCAL")).getId());
        }
        return ids;
    }

    @Test
    void twoHundredUsersOneSeatExactlyOneWins() throws Exception {
        long event = createEvent(1, 1);
        long seat = seatIds(event).get(0);
        List<Long> people = userIds(200);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long u : people) {
                pool.submit(() -> {
                    start.await();
                    try {
                        bookings.hold(u, event, List.of(seat));
                        ok.incrementAndGet();
                    } catch (ApiException e) {
                        conflict.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        }
        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(199);
    }

    @Test
    void overlappingMultiSeatRequestsNeverShareASeat() throws Exception {
        long event = createEvent(2, 10); // 20 seats
        List<Long> seats = seatIds(event);
        List<Long> people = userIds(120);
        ConcurrentLinkedQueue<Booking> won = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        Random rnd = new Random(42);
        Map<Long, List<Long>> wants = new HashMap<>();
        for (long u : people) {
            List<Long> copy = new ArrayList<>(seats);
            Collections.shuffle(copy, rnd);
            wants.put(u, copy.subList(0, 1 + rnd.nextInt(3)));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            for (long u : people) {
                pool.submit(() -> {
                    start.await();
                    try {
                        won.add(bookings.hold(u, event, wants.get(u)));
                    } catch (ApiException ignored) {
                        // lost the race for at least one seat
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
        Map<Long, Long> owner = new HashMap<>();
        for (Booking b : won) {
            for (var s : b.getSeats()) {
                Long prev = owner.put(s.getId(), b.getId());
                assertThat(prev).as("seat %d held by two bookings", s.getId()).isNull();
            }
        }
        assertThat(won).isNotEmpty();
        System.out.printf("overlapping race: %d of %d requests won, %d/20 seats held%n", won.size(), people.size(), owner.size());
    }

    @Test
    void redisLocksAreAllOrNothingAndOwnerOnly() {
        long event = 999_000 + new Random().nextInt(1000);
        assertThat(locks.acquire(event, List.of(1L, 2L), "a", Duration.ofSeconds(30))).isTrue();
        assertThat(locks.acquire(event, List.of(2L, 3L), "b", Duration.ofSeconds(30))).isFalse();
        assertThat(locks.owner(event, 3L)).isNull(); // nothing partially locked
        assertThat(locks.release(event, List.of(1L, 2L), "b")).isZero(); // not the owner
        assertThat(locks.release(event, List.of(1L, 2L), "a")).isEqualTo(2);
        assertThat(locks.acquire(event, List.of(2L, 3L), "b", Duration.ofSeconds(30))).isTrue();
    }
}
