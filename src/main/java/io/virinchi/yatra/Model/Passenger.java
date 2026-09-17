package io.virinchi.yatra.Model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class Passenger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    //Passenger 1:N ko "many" side — booking bina passenger hudaina.
    @ManyToOne
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    //Mr / Ms / Mrs / Master — wizard ko passenger form bata.
    private String title;

    private String firstName;
    private String lastName;

    //ADT / CHD — search ko adult/child split sanga match hunxa (INF lai form hudaina,
    //lap infant ho, so yaha INF record pani hudaina).
    private String passengerType;

    private String nationality;

    //Boarding pass ma assign hunxa (check-in paxi).
    private String seatNumber;
}
