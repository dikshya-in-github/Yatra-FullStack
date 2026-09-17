package io.virinchi.yatra.Model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
//(flight_id, seat_number) unique hunaiparxa — double-booking rokhne database-level
//guarantee yahi ho. Phase 6 ma duita same seat ko request aayo vane yo constraint
//le DataIntegrityViolationException throw garxa, ani 409 "Seat already booked" dinxa.
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_seat_flight_number",
        columnNames = {"flight_id", "seat_number"}))
@Data
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    //Seat 1:N ko "many" side — kun flight ko seat ho.
    @ManyToOne
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;

    //"1A", "12C" jastai seat number.
    @Column(name = "seat_number")
    private String seatNumber;

    //AVAILABLE / BOOKED — booking confirm huda BOOKED hunxa (Phase 6).
    private String status;
}
