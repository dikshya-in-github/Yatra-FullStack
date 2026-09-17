package io.virinchi.yatra.Model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    //Booking 1:1 Payment — yo side le FK rakhxa (booking_id), ani unique hunuparxa:
    //ek booking ma dherai payment row hunu bhayena.
    @OneToOne
    @JoinColumn(name = "booking_id", unique = true, nullable = false)
    private Booking booking;

    //eSewa matra integrated xa aile (card/bank/Khalti intentionally blocked).
    private String method;

    //Gateway ko transaction reference — mock ma "9A48291357" jastai.
    @Column(unique = true)
    private String txnId;

    //Gateway le charge gareko amount — BigDecimal (R5), DECIMAL(10,2).
    //Yo value Booking.totalAmount sanga exactly match hunuparxa, tesaile duitai
    //eutai type ma hunu parxa; double ra BigDecimal ko mix le compare garna
    //galat (==) banaunxa.
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    //PENDING / SUCCESS / FAILED / REFUNDED — payment SUCCESS bhayepaxi
    //booking CONFIRMED hunxa, ani ticket generate hunxa (Phase 10).
    private String status;

    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
