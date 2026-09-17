package io.virinchi.yatra;

import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 — money is exact, and this is what "exact" is checked to mean.
 *
 * <p>Three different claims, because "we changed the type" is not a proof:
 *
 * <ol>
 *   <li>a two-decimal amount survives a JPA write/read cycle unchanged;</li>
 *   <li>the <b>database</b> hands the value back as an exact decimal — a native
 *       query returns a `BigDecimal`, which it would not for a `double` column;</li>
 *   <li>decimal arithmetic on those values is exact, which is the actual reason
 *       `double` was wrong: it cannot represent 1234.56, so summing money drifts
 *       and the customer gets charged the drift.</li>
 * </ol>
 *
 * <p>{@code @Transactional} at class level — everything rolls back, so the live
 * database keeps no rows from a test run.
 */
@SpringBootTest
@Transactional
class MoneyPrecisionTest {

    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;

    @PersistenceContext private EntityManager em;

    @Test
    void aTwoDecimalAmountSurvivesTheRoundTripUnchanged() {
        Booking saved = bookingWithTotal("MP R1", new BigDecimal("16599.98"));
        int id = saved.getId();

        em.flush();
        em.clear();   // force a real read, not the in-memory instance

        BigDecimal reloaded = bookings.findById(id).orElseThrow().getTotalAmount();

        // compareTo, never equals: BigDecimal.equals is scale-sensitive, so
        // 7000.equals(7000.00) is false and that comparison would flake here.
        assertThat(reloaded).isEqualByComparingTo("16599.98");
        assertThat(flights.findById(saved.getFlight().getId()).orElseThrow().getFare())
                .isEqualByComparingTo("8299.99");
    }

    /**
     * The type-level proof: the driver hands back a {@code BigDecimal}. On a
     * `double` column this same query returns a {@code Double} and the assertion
     * fails — which is exactly how a silent revert to `double` would be caught.
     */
    @Test
    void theDatabaseReturnsAnExactDecimalNotAFloatingPointApproximation() {
        Booking saved = bookingWithTotal("MP R2", new BigDecimal("8299.99"));
        em.flush();

        Object raw = em.createNativeQuery("SELECT total_amount FROM booking WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(raw).isInstanceOf(BigDecimal.class);
        assertThat(raw).isNotInstanceOf(Double.class);
        assertThat((BigDecimal) raw).isEqualByComparingTo("8299.99");
    }

    /**
     * The bug R5 removed, written as arithmetic instead of prose.
     *
     * <p>The third assertion is deliberately about the <i>exact</i> value a double
     * holds: 3703.68 is not representable in binary, so no sum of doubles can ever
     * equal it exactly — whereas the same sum in `BigDecimal` does, every time.
     */
    @Test
    void decimalArithmeticIsExactWhereDoubleIsNot() {
        double doubleSum = 1234.56 + 1234.56 + 1234.56;
        BigDecimal decimalSum = new BigDecimal("1234.56")
                .add(new BigDecimal("1234.56"))
                .add(new BigDecimal("1234.56"));

        assertThat(new BigDecimal(doubleSum))
                .as("the exact value held by a double is not 3703.68 — this is why money must not be a double")
                .isNotEqualByComparingTo("3703.68");
        assertThat(decimalSum)
                .as("decimal money arithmetic is exact")
                .isEqualByComparingTo("3703.68");
        assertThat(decimalSum.scale()).as("and keeps the currency scale").isEqualTo(2);
    }

    /* ---------- fixture ---------- */

    /** Minimal booking that satisfies the schema's NOT NULL columns and money fields. */
    private Booking bookingWithTotal(String tag, BigDecimal total) {
        Flight flight = new Flight();
        flight.setFlightNo(tag);
        flight.setFare(new BigDecimal("8299.99"));
        flight.setSeatCapacity(60);
        flight.setStatus("Active");
        flights.saveAndFlush(flight);

        Booking booking = new Booking();
        booking.setFlight(flight);
        booking.setBookingStatus("PENDING");
        booking.setPaymentStatus("Pending");
        booking.setFareClass("E Class");
        booking.setTotalAmount(total);
        booking.setProductAmount(total);
        booking.setCreatedAt(LocalDateTime.now());
        return bookings.saveAndFlush(booking);
    }
}
