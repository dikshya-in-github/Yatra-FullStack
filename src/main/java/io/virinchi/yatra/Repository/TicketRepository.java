package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Integer> {

    //Ek booking ko ek ticket (1:1 — booking_id unique).
    Optional<Ticket> findByBookingId(int bookingId);

    //Admin ticket search: PNR wa ticket number le. Duitai unique hunuparxa.
    Optional<Ticket> findByPnr(String pnr);
    Optional<Ticket> findByTicketNo(String ticketNo);
    boolean existsByPnr(String pnr);

    List<Ticket> findByStatus(String status);
}
