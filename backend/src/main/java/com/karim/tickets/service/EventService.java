package com.karim.tickets.service;

import com.karim.tickets.domain.Event;
import com.karim.tickets.domain.Seat;
import com.karim.tickets.repo.Repos.EventRepository;
import com.karim.tickets.repo.Repos.SeatRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    /** One block of seats: {@code rows} rows (A, B, ...) of {@code seatsPerRow} seats at one price. */
    public record SectionSpec(String name, int rows, int seatsPerRow, int priceCents) {}

    private final EventRepository events;
    private final SeatRepository seats;

    public EventService(EventRepository events, SeatRepository seats) {
        this.events = events;
        this.seats = seats;
    }

    @Transactional
    public Event create(String name, String venue, Instant startsAt, String description, List<SectionSpec> sections) {
        Event e = events.save(new Event(name, venue, startsAt, description));
        List<Seat> created = new ArrayList<>();
        for (SectionSpec s : sections) {
            if (s.rows() < 1 || s.rows() > 26 || s.seatsPerRow() < 1 || s.seatsPerRow() > 100) {
                throw ApiException.bad("INVALID_LAYOUT", "rows must be 1-26 and seats per row 1-100");
            }
            for (int r = 0; r < s.rows(); r++) {
                String row = String.valueOf((char) ('A' + r));
                for (int n = 1; n <= s.seatsPerRow(); n++) {
                    created.add(new Seat(e.getId(), s.name(), row, n, s.priceCents()));
                }
            }
        }
        seats.saveAll(created);
        return e;
    }

    public List<Event> list() {
        return events.findAllByOrderByStartsAtAsc();
    }

    public Event get(long id) {
        return events.findById(id).orElseThrow(() -> ApiException.notFound("event"));
    }

    public List<Seat> seats(long eventId) {
        get(eventId);
        return seats.findByEventIdOrderBySectionAscRowAscNumberAsc(eventId);
    }
}
