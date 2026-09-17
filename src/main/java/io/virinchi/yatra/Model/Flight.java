package io.virinchi.yatra.Model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    //"U4 951" jastai display flight number — admin panele yahi dekhaunxa.
    @Column(unique = true)
    private String flightNo;

    //Flight 1:N relation ko "many" side — Airline bina flight hudaina.
    @ManyToOne
    @JoinColumn(name = "airline_id")
    private Airline airline;

    //Route ko dui tira pani Destination row ho (Roadmap Phase 2: "Destination
    //referenced by Flight (origin/destination)").
    @ManyToOne
    @JoinColumn(name = "origin_id")
    private Destination origin;

    @ManyToOne
    @JoinColumn(name = "destination_id")
    private Destination destination;

    //Flight kkun din ko ho — search/filter date le hune bhayeko le (Phase 5).
    private LocalDate flightDate;

    private LocalTime departTime;
    private LocalTime arriveTime;
    private String aircraft;

    //Base economy fare — fare class ko delta yahi mathi thapinxa (E/C/D/B/A/Y).
    //BigDecimal + DECIMAL(10,2) (R5): double le 8299.99 jastai value exactly hold
    //garna sakdaina (0.1 + 0.2 = 0.30000000000000004), ani tyo error booking total
    //hudai gateway ko amount samma pugxa.
    //Money arithmetic: BigDecimal.valueOf(pax) / new BigDecimal("8299.99") —
    //new BigDecimal(8299.99) (double constructor) kabhi na.
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fare;

    //Admin le EKCHOTI matra seat capacity set garxa.
    //Available seats kahile pani store hudaina: available = seatCapacity − COUNT(BOOKED seats).
    //Yo teacher le repeatedly flag gareko rule ho (Master Plan "Core Business Rule").
    private int seatCapacity;

    //Active / Cancelled
    private String status;

    //Flight 1:N Seat — flight banauda capacity jati Seat row auto-generate hunxa
    //(Phase 5), so cascade + orphanRemoval rakhna parxa.
    @OneToMany(mappedBy = "flight", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Seat> seats = new ArrayList<>();

    //Flight 1:N Booking — cascade chaina: flight delete garda bookings delete hunna.
    @OneToMany(mappedBy = "flight")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Booking> bookings = new ArrayList<>();
}
