package com.karim.tickets.config;

import com.karim.tickets.domain.Enums.Role;
import com.karim.tickets.domain.User;
import com.karim.tickets.repo.Repos.UserRepository;
import com.karim.tickets.service.BookingService;
import com.karim.tickets.service.EventService;
import com.karim.tickets.service.EventService.SectionSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Background jobs: expiring unpaid holds, and optional demo data. */
public final class Jobs {
    private Jobs() {}

    @Component
    public static class HoldExpiryJob {
        private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
        private final BookingService bookings;

        public HoldExpiryJob(BookingService bookings) {
            this.bookings = bookings;
        }

        @Scheduled(fixedDelayString = "${app.expiry-interval:15s}")
        public void run() {
            int n = bookings.expireHolds();
            if (n > 0) {
                log.info("expired {} unpaid holds", n);
            }
        }
    }

    /** SEED_DATA=true: an admin, a demo user and two events. Passwords come from env, never from code. */
    @Component
    public static class DemoSeeder implements ApplicationRunner {
        private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
        private final AppProperties props;
        private final UserRepository users;
        private final PasswordEncoder passwords;
        private final EventService events;

        public DemoSeeder(AppProperties props, UserRepository users, PasswordEncoder passwords, EventService events) {
            this.props = props;
            this.users = users;
            this.passwords = passwords;
            this.events = events;
        }

        @Override
        public void run(ApplicationArguments args) {
            var seed = props.seed();
            if (seed == null || !seed.enabled() || users.count() > 0) {
                return;
            }
            if (isBlank(seed.adminPassword()) || isBlank(seed.userPassword())) {
                log.warn("SEED_DATA=true but SEED_ADMIN_PASSWORD / SEED_USER_PASSWORD are not set; skipping seed");
                return;
            }
            users.save(new User("admin@tickets.local", passwords.encode(seed.adminPassword()), "Admin", Role.ADMIN, "LOCAL"));
            users.save(new User("demo@tickets.local", passwords.encode(seed.userPassword()), "Demo User", Role.USER, "LOCAL"));
            Instant soon = Instant.now().plus(Duration.ofDays(30));
            events.create("Coldplay: Music of the Spheres", "DY Patil Stadium, Mumbai", soon,
                    "Stadium concert", List.of(new SectionSpec("Gold", 4, 20, 450000), new SectionSpec("Silver", 6, 25, 250000)));
            events.create("IPL Final", "M. A. Chidambaram Stadium, Chennai", soon.plus(Duration.ofDays(10)),
                    "Cricket", List.of(new SectionSpec("Pavilion", 5, 20, 300000), new SectionSpec("Stand", 8, 30, 120000)));
            log.info("seeded demo users and events");
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }
}
