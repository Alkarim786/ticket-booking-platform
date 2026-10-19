package com.karim.tickets.domain;

import com.karim.tickets.domain.Enums.SeatStatus;
import jakarta.persistence.*;

@Entity
@Table(name = "seats")
public class Seat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false) private Long eventId;
    @Column(nullable = false) private String section;
    @Column(name = "row_label", nullable = false) private String row;
    @Column(nullable = false) private int number;
    @Column(nullable = false) private int priceCents;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SeatStatus status = SeatStatus.AVAILABLE;
    /** Optimistic lock: of two transactions that both read AVAILABLE, only one can write HELD. */
    @Version private long version;

    protected Seat() {}

    public Seat(Long eventId, String section, String row, int number, int priceCents) {
        this.eventId = eventId;
        this.section = section;
        this.row = row;
        this.number = number;
        this.priceCents = priceCents;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getSection() { return section; }
    public String getRow() { return row; }
    public int getNumber() { return number; }
    public int getPriceCents() { return priceCents; }
    public SeatStatus getStatus() { return status; }
    public void setStatus(SeatStatus status) { this.status = status; }
    public long getVersion() { return version; }
}
