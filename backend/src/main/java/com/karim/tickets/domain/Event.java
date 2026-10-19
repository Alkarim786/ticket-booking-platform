package com.karim.tickets.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String venue;
    @Column(nullable = false) private Instant startsAt;
    private String description;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    protected Event() {}

    public Event(String name, String venue, Instant startsAt, String description) {
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public String getDescription() { return description; }
}
