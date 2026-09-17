package io.virinchi.yatra.Model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    //Booking 1:1 Ticket — FK booking_id, unique: ek booking ko ek ticket matra.
    @OneToOne
    @JoinColumn(name = "booking_id", unique = true, nullable = false)
    private Booking booking;

    //PNR (Passenger Name Record) — "YTRA26" jastai. Admin panel le PNR/ticket number
    //le search garxa (Phase 12), so both unique hunuparxa.
    @Column(unique = true)
    private String pnr;

    @Column(unique = true)
    private String ticketNo;

    //ISSUED / CANCELLED
    private String status;

    private LocalDateTime issuedAt;
}
