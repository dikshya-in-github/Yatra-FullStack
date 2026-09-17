package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PassengerRepository extends JpaRepository<Passenger, Integer> {

    //Ek booking ka sabai passengers — eticket ko passenger table yahi bata render hunxa.
    List<Passenger> findByBookingId(int bookingId);

    //Booking confirm garda kati passenger ho — totalAmount verify garna kaam lagxa.
    long countByBookingId(int bookingId);
}
