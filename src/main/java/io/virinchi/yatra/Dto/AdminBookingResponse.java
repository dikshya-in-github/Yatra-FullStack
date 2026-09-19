package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Model.Ticket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * One booking as the admin table and its detail modal see it — the mock's record
 * shape, transcribed key by key from {@code assets/js/admin-bookings.js}.
 *
 * <h2>The page is the contract, and it is a stricter one than it looks</h2>
 * <p>{@code admin-bookings.js} does three things to this record that a
 * <i>near</i>-correct body would break silently rather than loudly:
 *
 * <ul>
 *   <li><b>{@code id} must be a <i>string</i>.</b> The search filter runs
 *       {@code b.id.toLowerCase().includes(q)} — an integer id would throw a
 *       {@code TypeError} the moment the admin types in the search box, which the
 *       page's {@code .catch()} would turn into a silent fall back to its
 *       localStorage seeds. So the numeric primary key is rendered as text here
 *       ({@code "12"}), and {@code GET /api/admin/bookings/{id}} accepts that same
 *       text back as a path variable.</li>
 *   <li><b>{@code status} must be the page's display vocabulary</b> —
 *       {@code "Confirmed"} / {@code "Cancelled"} / {@code "Pending"} — not the
 *       entity's storage vocabulary ({@code CONFIRMED} / {@code CANCELLED} /
 *       {@code PENDING}). Three page behaviours read it directly:
 *       {@code bookingBadge(s)} colours the chip, {@code canCancel = b.status ===
 *       'Confirmed'} decides whether the cancel button is drawn at all, and the
 *       filter dropdown's options are the title-case values. An uppercase body
 *       would render every booking with a red "danger" badge and remove the
 *       cancel action, with no error anywhere — see the class note in
 *       {@code Service/BookingService} for where that mapping lives.</li>
 *   <li><b>{@code pnr} / {@code ticketNo} / {@code customer} / {@code email} /
 *       {@code phone} must be strings, never {@code null}.</b>
 *       {@code normalizeRecord} repairs a non-string {@code pnr} to {@code ""},
 *       but the customer and contact fields go straight into
 *       {@code escapeHtml(...)} — and {@code String(null)} renders "null". The
 *       factories below emit {@code ""} for an absent value, the same call
 *       {@code FlightResponse} makes for an absent airport code.</li>
 * </ul>
 *
 * <h2>What is deliberately <i>not</i> here</h2>
 * <p>The booking's {@code user} (owner) is absent because the mock's record has no
 * such key, and the page's detail modal renders the <i>contact</i> block rather
 * than the account — {@code booking.contactName} is what the admin manages, and a
 * guest booking has no user at all (the wizard is public, so {@code user_id} is
 * nullable). The {@code seeded} marker is absent for a second and firmer reason:
 * {@code bit(1)} is a reset implementation detail, not part of the JSON contract
 * (R14).
 *
 * <h2>Three keys are additions, and all three are safe</h2>
 * <p>{@code passengers[].seatNumber} comes from the real schema and no page reads it
 * yet (the detail modal's passenger table has no seat column today) — extra keys break
 * nothing because every page reads named fields. {@code ticketNo} is kept flat rather
 * than nested only to match the mock; {@link PaymentRow} and the flight block stay
 * nested because they are objects there.
 *
 * <p>{@code ticketStatus} is the one with a reader, and it exists because the Tickets
 * page could not get the answer without it. That page's Status column used to be derived
 * from the <i>booking's</i> status ({@code 'Cancelled' → "Voided", otherwise "Issued"})
 * instead of being read from the ticket's own {@code ISSUED}/{@code CANCELLED} column —
 * and those two are independent states, not two spellings of one:
 *
 * <ul>
 *   <li><b>Nothing in the API voids a document.</b> {@code TicketService.issue} writes
 *       {@code ISSUED} when a payment settles, {@code PaymentService.refund} moves money
 *       and touches nothing else, and cancelling a booking leaves the ticket alone — so a
 *       cancelled booking's document is still {@code ISSUED}, and the derived label
 *       painted it "Voided".</li>
 *   <li><b>The demo's one {@code CANCELLED} ticket is the seeder's</b>, on a booking whose
 *       status {@code SeedService} also seeds as cancelled — which is why the derived
 *       label looked right everywhere until a real cancellation went through. A refund is
 *       not what voids a ticket, and a cancellation is not what voids one either: only the
 *       seeder has ever written that value.</li>
 * </ul>
 *
 * <p>So the page now shows both: the badge is the document's state and the sub-line under
 * it is the booking's. Nothing reads the field for a booking with no ticket — it is
 * {@code ""}, like every other absent string here.
 */
public record AdminBookingResponse(

        /** The numeric primary key, rendered as text — see the class note. */
        String id,

        /** From the 1:1 ticket; {@code ""} while the booking is still pending. */
        String pnr,
        String ticketNo,

        /**
         * The <b>ticket row's own</b> {@code ISSUED}/{@code CANCELLED}, blank when there is
         * no document. Deliberately not the booking's status — see the class note.
         */
        String ticketStatus,

        /** The <b>display</b> vocabulary ({@code Confirmed}), not {@code CONFIRMED}. */
        String status,
        String paymentStatus,

        /** The contact block the wizard collected — the admin's "customer" column. */
        String customer,
        String email,
        String phone,

        List<PassengerRow> passengers,
        FlightRow flight,
        BigDecimal amount,
        BigDecimal productAmount,
        PaymentRow payment,
        LocalDateTime createdAt
) {

    /** The page's time inputs render {@code "HH:mm"}, the same format it accepts. */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Builds one row.
     *
     * <p><b>The passengers are passed in rather than read off the booking.</b>
     * {@code Booking.passengers} is a collection, and a collection cannot be
     * {@code @EntityGraph}-fetched alongside a paged query — Hibernate would fall
     * back to <i>in-memory</i> pagination, loading every matching booking to return
     * one page. So the service fetches the page of bookings, then batches the
     * passengers for exactly those ids in one query, and hands the result here.
     *
     * <p>The to-one associations ({@code flight} and its airline/airports,
     * {@code payment}, {@code ticket}) <i>are</i> graph-fetched by the repository,
     * so reading them costs nothing extra — but every one of them is still null
     * checked, because a booking with no payment row and no ticket is the normal
     * state for a draft and must not 500 the admin list.
     */
    public static AdminBookingResponse of(Booking booking, List<Passenger> passengers) {
        return new AdminBookingResponse(
                String.valueOf(booking.getId()),
                pnr(booking.getTicket()),
                ticketNo(booking.getTicket()),
                ticketStatus(booking.getTicket()),
                displayStatus(booking.getBookingStatus()),
                blankIfNull(booking.getPaymentStatus()),
                blankIfNull(booking.getContactName()),
                blankIfNull(booking.getContactEmail()),
                blankIfNull(booking.getContactPhone()),
                passengerRows(passengers),
                FlightRow.of(booking, passengers),
                booking.getTotalAmount(),
                booking.getProductAmount(),
                PaymentRow.of(booking.getPayment()),
                booking.getCreatedAt());
    }

    /* ------------------------------------------------------------------ *
     *  Nested rows — nested only in Java; the JSON is flat per object      *
     * ------------------------------------------------------------------ */

    /** One traveller, in passenger order — the order the detail modal numbers them. */
    public record PassengerRow(
            String title,
            String firstName,
            String lastName,
            String type,
            String nationality,
            String seatNumber
    ) {

        static PassengerRow of(Passenger passenger) {
            return new PassengerRow(
                    blankIfNull(passenger.getTitle()),
                    blankIfNull(passenger.getFirstName()),
                    blankIfNull(passenger.getLastName()),
                    blankIfNull(passenger.getPassengerType()),
                    blankIfNull(passenger.getNationality()),
                    blankIfNull(passenger.getSeatNumber()));
        }
    }

    /**
     * The flight block: the booking's own flight, with the keys the page reads.
     *
     * <p>{@code pricePerPassenger} is the flight row's fare and {@code flightClass}
     * is the <i>booking's</i> {@code fareClass} — the two live on different tables
     * and the page labels them differently, so mixing them up would quietly show a
     * different fare class than the one that was sold.
     */
    public record FlightRow(
            String flightNo,
            AirlineRow airline,
            String from,
            String to,
            LocalDate date,
            String depart,
            String arrive,
            String flightClass,
            boolean refundable,
            BigDecimal pricePerPassenger,
            int passengerCount
    ) {

        static FlightRow of(Booking booking, List<Passenger> passengers) {
            Flight flight = booking.getFlight();

            return new FlightRow(
                    flight == null ? "" : blankIfNull(flight.getFlightNo()),
                    AirlineRow.of(flight == null ? null : flight.getAirline()),
                    code(flight == null ? null : flight.getOrigin()),
                    code(flight == null ? null : flight.getDestination()),
                    flight == null ? null : flight.getFlightDate(),
                    time(flight == null ? null : flight.getDepartTime()),
                    time(flight == null ? null : flight.getArriveTime()),
                    blankIfNull(booking.getFareClass()),
                    booking.isRefundable(),
                    flight == null ? null : flight.getFare(),
                    passengers == null ? 0 : passengers.size());
        }
    }

    /** Just what the table's sub-line shows next to the flight number. */
    public record AirlineRow(String name, String iata) {

        static AirlineRow of(Airline airline) {
            return airline == null
                    ? new AirlineRow("", "")
                    : new AirlineRow(blankIfNull(airline.getName()), blankIfNull(airline.getIata()));
        }
    }

    /**
     * The gateway row, as the detail modal's "Payment" section reads it.
     *
     * <p>Emitted as an object with blank fields even when there is no payment row,
     * never {@code null}: the page's {@code normalizeRecord} replaces a missing
     * {@code payment} with a <i>default</i> object naming eSewa and an empty txn id,
     * which reads as a real (if empty) transaction. Blank fields are the honest
     * version of "this booking has not been paid yet".
     */
    public record PaymentRow(String method, String txnId, LocalDateTime paidAt) {

        static PaymentRow of(Payment payment) {
            return payment == null
                    ? new PaymentRow("", "", null)
                    : new PaymentRow(
                            blankIfNull(payment.getMethod()),
                            blankIfNull(payment.getTxnId()),
                            payment.getPaidAt());
        }
    }

    /* ------------------------------------------------------------------ *
     *  Mapping helpers                                                     *
     * ------------------------------------------------------------------ */

    /**
     * Storage vocabulary → display vocabulary, and nothing else.
     *
     * <p>One mapping, in one place, and the <i>only</i> reason it exists is the
     * frontend contract described at the top of this class. An unrecognised value
     * is passed through unchanged rather than guessed at: if a future status is
     * added to the entity, the admin sees exactly what is stored instead of a
     * silently mangled badge.
     */
    public static String displayStatus(String stored) {
        String value = blankIfNull(stored);
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "PENDING" -> "Pending";
            case "CONFIRMED" -> "Confirmed";
            case "CANCELLED" -> "Cancelled";
            default -> value;
        };
    }

    private static List<PassengerRow> passengerRows(List<Passenger> passengers) {
        return passengers == null
                ? List.of()
                : passengers.stream().map(PassengerRow::of).toList();
    }

    private static String pnr(Ticket ticket) {
        return ticket == null ? "" : blankIfNull(ticket.getPnr());
    }

    private static String ticketNo(Ticket ticket) {
        return ticket == null ? "" : blankIfNull(ticket.getTicketNo());
    }

    /** The document's own state, or {@code ""} when this booking has no document yet. */
    private static String ticketStatus(Ticket ticket) {
        return ticket == null ? "" : blankIfNull(ticket.getStatus());
    }

    /** A route endpoint is the airport code — {@code "KTM"}, not the city name. */
    private static String code(Destination destination) {
        return destination == null ? "" : blankIfNull(destination.getCode());
    }

    private static String time(LocalTime value) {
        return value == null ? "" : value.format(HH_MM);
    }

    /** The mock answers {@code ""}, never {@code null}, for an absent value. */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
