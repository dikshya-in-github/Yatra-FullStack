package io.virinchi.yatra.Model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    //Booking 1:N ko "many" side — kun user le book gareko.
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    //Kun flight book vayo — yo bina booking hudaina.
    @ManyToOne
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;

    //Contact details wizard ko step 2 bata (booking.html ko contact form).
    private String contactName;
    private String contactEmail;
    private String contactPhone;

    //PENDING / CONFIRMED / CANCELLED — payment SUCCESS hunasath CONFIRMED.
    private String bookingStatus;

    //Pending / Paid / Refunded / Failed — Payment row ko status sanga match hunxa.
    private String paymentStatus;

    //Wizard ma select gareko fare class + refundable flag ("E Class" ...).
    private String fareClass;
    private boolean refundable;

    //totalAmount = fare × paying passengers — gateway le charge garne amount,
    //ani yahi value MockDB.payableTotal() le pani dinxa.
    //BigDecimal — multiply garda fare.multiply(BigDecimal.valueOf(passengers)),
    //ani scale 2 ma setScale(..., RoundingMode.HALF_UP) garera rakhne.
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    //discount agadi ko amount (gateway request ma janxa)
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal productAmount;

    private LocalDateTime createdAt;

    //Seed marker (Phase 7) — true only for the demo bookings the seeder created.
    //This is what lets `POST /api/admin/reset` remove a *paid and ticketed* demo
    //booking: R4's policy (a sale record is cancelled, never hard-deleted) still
    //governs the admin APIs, and the reset is the one deliberately separate
    //operation the standards' open question #6 asked for.
    //(See Model/Airline for the full marker note.)
    @Column(nullable = false)
    private boolean seeded;

    //Booking 1:N Passenger — booking sangै passengers save hunxa.
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Passenger> passengers = new ArrayList<>();

    //Booking 1:1 Payment — FK payment table ma, so yo inverse side.
    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Payment payment;

    //Booking 1:1 Ticket — PNR/ticket number yaha hudaina, Ticket row ma hunxa.
    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Ticket ticket;
}
