package io.virinchi.yatra.Dto;

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
import java.util.ArrayList;
import java.util.List;

/**
 * ONE booking, as its own customer sees it — the read behind {@code eticket.html}.
 *
 * <p>This is the storefront's counterpart to {@link AdminBookingResponse}: the
 * fields a passenger's own ticket carries, and nothing an admin screen needs.
 * Before it existed, the e-ticket page rendered from {@code sessionStorage} and
 * <b>invented</b> the two identifiers a ticket is worth anything for — it built a
 * PNR and a ticket number out of the transaction id ({@code "DEMO" + Date.now()}
 * when there was none), so the numbers on the printed document were derived from a
 * string the browser made up. They now come from the {@code tickets} row.
 *
 * <p><b>Why not reuse {@link AdminBookingResponse}?</b> It answers a different
 * question — it carries the admin list's search columns (customer, email, phone,
 * ticket status, hold expiry) and none of the ticket's own detail (departure and
 * arrival times, the cities, the passengers, the payment's method and timestamp).
 * The two agree on {@code bookingId} and the amounts and are otherwise unrelated.
 *
 * <p>Fields that do not exist yet are {@code null} or empty rather than fabricated:
 * a booking that has not been paid for has no {@code pnr}, no {@code ticketNo} and
 * no {@code issuedAt}, and the page renders the absence rather than a placeholder.
 * See {@link #of(Booking, Ticket, Payment, List)}, which is the only place that
 * mapping is decided.
 *
 * <p><b>The ticket, payment and passengers are passed in, not read off the
 * booking.</b> All three are the <i>inverse</i> side of their relationships (the
 * foreign keys live on {@code ticket}, {@code payment} and {@code passenger}), and
 * Hibernate does not maintain an inverse collection or {@code OneToOne} in memory
 * when the owning row is written: something that creates a ticket by setting
 * {@code ticket.booking} leaves the {@code Booking} instance already in the
 * persistence context still reporting no ticket. In a fresh request that is
 * invisible — the lazy read goes to the database and finds it — but it is a trap for
 * any caller that has the booking loaded, which is why
 * {@code BookingService.getBooking} already fetches passengers the same way.
 */
public record BookingDetailResponse(

        /* ---------- the booking ---------- */
        int bookingId,
        String status,
        String paymentStatus,
        BigDecimal amount,
        BigDecimal productAmount,
        String fareClass,
        boolean refundable,
        LocalDateTime createdAt,

        /* ---------- the ticket, empty until a payment settles ---------- */
        String pnr,
        String ticketNo,
        String ticketStatus,
        LocalDateTime issuedAt,

        /* ---------- the flight ---------- */
        String flightNo,
        String airlineName,
        String airlineCode,
        String fromCode,
        String fromCity,
        String toCode,
        String toCity,
        LocalDate date,
        LocalTime depart,
        LocalTime arrive,
        int durationMinutes,

        /* ---------- how it was paid ---------- */
        String method,
        String txnId,
        LocalDateTime paidAt,

        /* ---------- who is flying ---------- */
        List<PassengerRow> passengers
) {

    /** A passenger as the ticket prints them: one name, and the seat if it is allocated. */
    public record PassengerRow(String name, String type, String seat) {

        private static PassengerRow of(Passenger passenger) {
            String name = ((blank(passenger.getFirstName()) ? "" : passenger.getFirstName().trim())
                    + " "
                    + (blank(passenger.getLastName()) ? "" : passenger.getLastName().trim())).trim();
            return new PassengerRow(
                    name,
                    passenger.getPassengerType() == null ? "" : passenger.getPassengerType(),
                    passenger.getSeatNumber() == null ? "" : passenger.getSeatNumber());
        }
    }

    public static BookingDetailResponse of(Booking booking, Ticket ticket, Payment payment,
                                           List<Passenger> rows) {
        Flight flight = booking.getFlight();

        List<PassengerRow> passengers = new ArrayList<>();
        if (rows != null) {
            rows.forEach(p -> passengers.add(PassengerRow.of(p)));
        }

        return new BookingDetailResponse(
                booking.getId(),
                text(booking.getBookingStatus()),
                text(booking.getPaymentStatus()),
                booking.getTotalAmount(),
                booking.getProductAmount(),
                text(booking.getFareClass()),
                booking.isRefundable(),
                booking.getCreatedAt(),

                ticket == null ? "" : text(ticket.getPnr()),
                ticket == null ? "" : text(ticket.getTicketNo()),
                ticket == null ? "" : text(ticket.getStatus()),
                ticket == null ? null : ticket.getIssuedAt(),

                flight == null ? "" : text(flight.getFlightNo()),
                (flight == null || flight.getAirline() == null) ? "" : text(flight.getAirline().getName()),
                (flight == null || flight.getAirline() == null) ? "" : text(flight.getAirline().getIata()),
                code(flight == null ? null : flight.getOrigin()),
                city(flight == null ? null : flight.getOrigin()),
                code(flight == null ? null : flight.getDestination()),
                city(flight == null ? null : flight.getDestination()),
                flight == null ? null : flight.getFlightDate(),
                flight == null ? null : flight.getDepartTime(),
                flight == null ? null : flight.getArriveTime(),
                durationMinutes(flight),

                payment == null ? "" : text(payment.getMethod()),
                payment == null ? "" : text(payment.getTxnId()),
                payment == null ? null : payment.getPaidAt(),

                passengers);
    }

    /**
     * Minutes between departure and arrival.
     *
     * <p>Computed here rather than in the page because the page used to subtract two
     * {@code "HH:mm"} strings and print {@code "45 min"} whenever either was missing,
     * which is a number no flight ever supplied. An unset time now yields {@code 0},
     * which the page renders as nothing.
     */
    private static int durationMinutes(Flight flight) {
        if (flight == null || flight.getDepartTime() == null || flight.getArriveTime() == null) {
            return 0;
        }
        return (int) java.time.Duration.between(flight.getDepartTime(), flight.getArriveTime()).toMinutes();
    }

    private static String code(Destination destination) {
        return destination == null ? "" : text(destination.getCode());
    }

    private static String city(Destination destination) {
        return destination == null ? "" : text(destination.getCity());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
