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

    //Ticket minting (Phase 10) checks both halves before it saves: both columns are
    //UNIQUE, and the derivation is deterministic on the gateway's txn id, so two
    //transactions 27.8 h apart can produce the same digits.
    boolean existsByTicketNo(String ticketNo);

    List<Ticket> findByStatus(String status);
}
