package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Airline;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * The admin list's search: <b>name OR IATA code</b>, one optional status, sorted.
     *
     * <p>Two reasons this is a query rather than a derived method. The derived
     * {@code findByNameContainingIgnoreCase} can only match ONE column, and the page
     * it serves promises both — {@code admin-airlines.html}'s toolbar is "Search name
     * or IATA code…", and an admin typing {@code U4} expects Buddha Air. A second
     * reason is the nulls: spring-data is literal about a null argument, so
     * {@code ...AndStatus(null)} would generate {@code status is null} and match
     * nothing rather than "any status", which is why every optional filter here is
     * written as {@code :param is null or ...}. {@link #searchPage} is the same query,
     * one page at a time.
     *
     * <p>{@code search} is {@code null} when nothing was typed and a
     * {@code %term%} string otherwise — {@code FlightRepository.searchAll} takes its
     * term the same way, so the two list endpoints agree on what a blank search means.
     *
     * <p>The {@code Sort} overload is what makes an <b>unpaged</b> list deterministic:
     * a Pageable carries its own Sort, but {@code Pageable.unpaged()} discards it, so
     * "all rows, in a stable order" needs the sort to travel separately (R6).
     */
    @Query("""
            select a from Airline a
            where (:search is null
                   or lower(a.name) like :search
                   or lower(a.iata) like :search)
              and (:status is null or a.status = :status)
            """)
    List<Airline> searchAll(@Param("search") String search,
                            @Param("status") String status,
                            Sort sort);

    /** The same filters, one page at a time. */
    @Query("""
            select a from Airline a
            where (:search is null
                   or lower(a.name) like :search
                   or lower(a.iata) like :search)
              and (:status is null or a.status = :status)
            """)
    Page<Airline> searchPage(@Param("search") String search,
                             @Param("status") String status,
                             Pageable pageable);
}
