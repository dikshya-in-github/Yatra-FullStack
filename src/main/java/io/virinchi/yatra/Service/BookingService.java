package io.virinchi.yatra.Service;

import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import io.virinchi.yatra.Repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Booking lifecycle — cancel and delete (R4).
 *
 * <p><b>Why a hard delete needs an owner.</b> A booking is the parent of
 * passengers, a payment and a ticket. Probed against the real TiDB schema,
 * {@code bookingRepository.delete(booking)} behaves differently depending on
 * what is already in the persistence context:
 *
 * <ul>
 *   <li>with the children <i>managed</i> (any service that validates before it
 *       deletes will have loaded them) it fails with
 *       {@code TransientPropertyValueException} — Hibernate's pre-flush
 *       transient-reference check ({@code CascadingActions.isChildTransient})
 *       treats a <b>deleted</b> parent as an unsaved transient one whenever the
 *       child's FK is not DB-level delete-cascaded, so
 *       {@code cascade = ALL} on the collection does <b>not</b> rescue it;</li>
 *   <li>with a clean session it happens to succeed.</li>
 * </ul>
 *
 * <p>So "delete the parent and trust the cascade" is order-dependent luck. This
 * service removes the children first, in dependency order, which is the ordering
 * the probe proved works in both situations.
 *
 * <p><b>The policy on top of the mechanism:</b> {@link #deleteBooking(int)} is
 * deliberately restricted to bookings that have <i>no</i> payment and no ticket
 * (an abandoned/pending attempt). A booking that was paid for is a sale record —
 * the correct admin action is {@link #cancelBooking(int)}, exactly as the
 * frontend's own confirm dialog says ("Seats stay counted on the flight; the
 * refund itself is completed by the payments flow"). The payment/ticket FKs keep
 * no DB-level cascade, so the database refuses that delete too: the rule is
 * enforced twice, and the service check only exists to turn it into a clean 409.
 */
@Service
public class BookingService {

    private static final String CANCELLED = "CANCELLED";
    private static final String BOOKED = "BOOKED";
    private static final String AVAILABLE = "AVAILABLE";

    private final BookingRepository bookings;
    private final PassengerRepository passengers;
    private final PaymentRepository payments;
    private final TicketRepository tickets;
    private final SeatRepository seats;

    public BookingService(BookingRepository bookings,
                          PassengerRepository passengers,
                          PaymentRepository payments,
                          TicketRepository tickets,
                          SeatRepository seats) {
        this.bookings = bookings;
        this.passengers = passengers;
        this.payments = payments;
        this.tickets = tickets;
        this.seats = seats;
    }

    /**
     * Soft cancel — the only "delete" a customer-facing booking ever gets.
     *
     * <p>Flips {@code bookingStatus} to {@code CANCELLED} and touches nothing
     * else: the payment row is left for the refund flow (Phase 10) to update, and
     * the seats stay {@code BOOKED} because the frontend tells the admin exactly
     * that. Idempotent, so a double-click is harmless.
     */
    @Transactional
    public Booking cancelBooking(int bookingId) {
        Booking booking = require(bookingId);
        if (CANCELLED.equalsIgnoreCase(booking.getBookingStatus())) {
            return booking;   // already cancelled — no-op, not an error
        }
        booking.setBookingStatus(CANCELLED);
        //paymentStatus jaanatan chhodeko: refund PaymentService (Phase 10) le garda hunxa,
        //ani seat rows pani BOOKED nai rahanxan (frontend ko cancel message sanga match hunxa).
        return bookings.save(booking);
    }

    /**
     * Hard delete, restricted to bookings with no sale records — demo/abandoned
     * data only. Refuses with 409 otherwise (see the class docs for why).
     */
    @Transactional
    public void deleteBooking(int bookingId) {
        Booking booking = require(bookingId);

        if (payments.findByBookingId(bookingId).isPresent()
                || tickets.findByBookingId(bookingId).isPresent()) {
            throw ConflictException.bookingHasSaleRecords(bookingId);
        }

        List<Passenger> bookingPassengers = passengers.findByBookingId(bookingId);
        releaseSeats(booking.getFlight(), bookingPassengers);

        //Delete order (R4): children pahile, parent paxi — natra Hibernate ko
        //pre-flush transient-reference check le TransientPropertyValueException
        //throw garxa (collection ma cascade = ALL hunu le pani bachdaina).
        passengers.deleteAll(bookingPassengers);
        passengers.flush();          // FK-safe order: passenger rows before the booking row
        bookings.delete(booking);
        bookings.flush();
    }

    /**
     * Frees the seat rows this booking held, so availability stays honest.
     *
     * <p>Rule 1 computes availability as {@code seatCapacity − COUNT(BOOKED
     * seats)}, so a seat left {@code BOOKED} by a deleted booking is a seat that
     * can never be sold again. Only seats that a passenger actually holds are
     * touched, and only cancel-free deletes reach here.
     */
    private void releaseSeats(Flight flight, List<Passenger> bookingPassengers) {
        if (flight == null) {
            return;
        }
        Set<String> seatNumbers = bookingPassengers.stream()
                .map(Passenger::getSeatNumber)
                .filter(Objects::nonNull)
                .filter(seatNumber -> !seatNumber.isBlank())
                .collect(Collectors.toSet());

        for (String seatNumber : seatNumbers) {
            seats.findByFlightIdAndSeatNumber(flight.getId(), seatNumber).ifPresent(seat -> {
                if (BOOKED.equalsIgnoreCase(seat.getStatus())) {
                    seat.setStatus(AVAILABLE);   // managed entity — dirty checking le flush garxa
                }
            });
        }
    }

    private Booking require(int bookingId) {
        return bookings.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));
    }
}
