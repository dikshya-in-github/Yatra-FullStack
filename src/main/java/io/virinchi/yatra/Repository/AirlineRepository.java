package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Airline;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AirlineRepository extends JpaRepository<Airline, Integer> {

    //IATA code (U4, YT, S3, ST) unique hunuparxa — flight number yahi bata bantinxa.
    boolean existsByIata(String iata);
    Optional<Airline> findByIata(String iata);

    //Admin list + customer storefront ko read path (Phase 4: search + pagination).
    List<Airline> findByStatus(String status);
    List<Airline> findByNameContainingIgnoreCase(String name);
    Page<Airline> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
