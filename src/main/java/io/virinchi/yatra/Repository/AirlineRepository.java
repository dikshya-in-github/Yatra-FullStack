package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Airline;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AirlineRepository extends JpaRepository<Airline, Integer> {

    //IATA code (U4, YT, S3, ST) unique hunuparxa — flight number yahi bata bantinxa.
    boolean existsByIata(String iata);
    Optional<Airline> findByIata(String iata);

    //The demo seeder's own rows (Phase 7) — what `POST /api/admin/reset` removes.
    List<Airline> findBySeededTrue();

    //Admin list + customer storefront ko read path (search + pagination).
    List<Airline> findByStatus(String status);
    List<Airline> findByNameContainingIgnoreCase(String name);
    Page<Airline> findByNameContainingIgnoreCase(String name, Pageable pageable);

    /*
     * The filter pairs below exist because BOTH filters are optional and Spring
     * Data is literal about a null argument: `...AndStatus(null)` generates
     * `status is null`, which matches nothing rather than "any status". So the
     * service picks the method that matches what the caller actually sent,
     * instead of passing nulls and wondering why the list came back empty.
     *
     * An empty `name` is fine — `Containing` turns it into `like '%%'`, which
     * matches every row.
     *
     * The `Sort` overloads are what make an UNPAGED list deterministic: a
     * Pageable carries its own Sort, but `Pageable.unpaged()` discards it, so
     * "all rows, in a stable order" needs the sort to travel separately (R6).
     */
    Page<Airline> findByNameContainingIgnoreCaseAndStatus(String name, String status, Pageable pageable);

    List<Airline> findByNameContainingIgnoreCase(String name, Sort sort);

    List<Airline> findByNameContainingIgnoreCaseAndStatus(String name, String status, Sort sort);
}
