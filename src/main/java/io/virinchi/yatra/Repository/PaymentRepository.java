package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Integer> {

    //Ek booking ko ek payment (1:1 — booking_id unique).
    Optional<Payment> findByBookingId(int bookingId);

    //Gateway reference unique — duplicate txn save hunna.
    Optional<Payment> findByTxnId(String txnId);

    //Admin payments monitoring: GET /api/admin/payments.
    List<Payment> findByStatus(String status);
    List<Payment> findByMethod(String method);
}
