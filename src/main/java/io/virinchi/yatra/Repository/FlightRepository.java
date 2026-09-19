package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Flight;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FlightRepository extends JpaRepository<Flight, Integer> {

    //Display flight number ("U4 951") unique hunuparxa.
    boolean existsByFlightNo(String flightNo);
    Optional<Flight> findByFlightNo(String flightNo);

    /**
     * The storefront's search: {@code GET /api/flights/search?origin=&destination=&date=}.
     *
     * <p><b>This replaced a derived method that nothing called.</b>
     * {@code findByOriginCodeAndDestinationCodeAndFlightDate} sat here from Phase 5 with
     * a comment naming exactly this endpoint — and no caller in {@code src/main} or
     * {@code src/test}, because the endpoint it was written for was never built. It is
     * the same shape as the {@code GET /api/admin/tickets/{bookingId}} defect the module
     * pass kept finding: a thing built for a page that never asked. When the endpoint
     * finally arrived it needed three things the derived method could not express, so
     * the method was replaced rather than left beside its successor:
     *
     * <ul>
     *   <li><b>Only sellable rows.</b> An {@code Inactive} flight is one the admin has
     *       taken out of service; listing it to a customer would be the storefront
     *       showing a flight the panel says is not flying.</li>
     *   <li><b>Ordered by departure, in SQL.</b> A search result is read top to bottom
     *       and the page prints the times; {@code SELECT} promises no order, and sorting
     *       the list in the service would sort only the rows a query happened to return.</li>
     *   <li><b>The airline and both airports fetched.</b> Every card draws the carrier
     *       and both ends, so {@code @EntityGraph} turns what would be three extra
     *       selects per row into joins.</li>
     * </ul>
     *
     * <p><b>Both codes are compared case-insensitively</b> ({@code upper(...) = :code} with
     * the service uppercasing what the page sent): {@code destination.code} is
     * {@code utf8mb4_bin}, so a lower-case {@code ktm} would otherwise match nothing and
     * the search would look empty rather than wrong — the same trap the admin query below
     * documents.
     *
     * <p>{@code date} is not nullable here: "any date" is not a search a customer makes,
     * and the service defaults a missing date to today before calling.
     */
    @EntityGraph(attributePaths = {"airline", "origin", "destination"})
    @Query("""
            select f from Flight f
            where upper(f.origin.code) = :origin
              and upper(f.destination.code) = :destination
              and f.flightDate = :date
              and upper(f.status) = 'ACTIVE'
            order by f.departTime asc
            """)
    List<Flight> searchStorefront(@Param("origin") String origin,
                                  @Param("destination") String destination,
                                  @Param("date") LocalDate date);

    //Admin flight list + airline-wise filter (airline/route/date/status).
    List<Flight> findByAirlineId(int airlineId);

    //The demo seeder's own rows (Phase 7) — what `POST /api/admin/reset` removes.
    List<Flight> findBySeededTrue();

    //Guard: "yo row kahin use hudai xa?" — airline/destination delete garna aghi.
    //Count query (not findByAirlineId(...).size()) so the guard does not load rows it
    //is only going to count.
    long countByAirlineId(int airlineId);
    long countByOriginIdOrDestinationId(int originId, int destinationId);
    List<Flight> findByFlightDate(LocalDate flightDate);
    List<Flight> findByStatus(String status);
    Page<Flight> findByStatus(String status, Pageable pageable);

    /**
     * The admin list: airline, route, date and status as independent filters.
     *
     * <p><b>One nullable-parameter query, not 32 derived ones.</b> Four
     * independent filters would be sixteen combinations, and adding a fifth later
     * would double that again — so each clause is written as
     * {@code :param is null or ...} and the service passes null for "no filter".
     *
     * <p><b>Codes are compared case-insensitively.</b> {@code destination.code}
     * is {@code utf8mb4_bin} (case-<i>sensitive</i>), so {@code UPPER(...)} on
     * both sides is what makes {@code from=ktm} match a stored {@code KTM} rather
     * than silently returning nothing.
     *
     * <p><b>Its own query, not {@code Pageable.unpaged()}.</b> Unpaged discards
     * the {@code Sort} — the R6 lesson — so the list path below keeps an
     * explicitly ordered query of its own.
     *
     * <p>Both are {@code @EntityGraph}-fetched: every response carries the
     * airline's id and both airport codes, which would otherwise be three extra
     * selects per row.
     */
    @EntityGraph(attributePaths = {"airline", "origin", "destination"})
    @Query("""
            select f from Flight f
            where (:search is null
                   or lower(f.flightNo) like :search
                   or lower(f.origin.code) like :search
                   or lower(f.destination.code) like :search
                   or lower(f.airline.name) like :search)
              and (:airlineId is null or f.airline.id = :airlineId)
              and (:origin is null or upper(f.origin.code) = :origin)
              and (:destination is null or upper(f.destination.code) = :destination)
              and (:date is null or f.flightDate = :date)
              and (:status is null or f.status = :status)
            """)
    List<Flight> searchAll(@Param("search") String search,
                           @Param("airlineId") Integer airlineId,
                           @Param("origin") String origin,
                           @Param("destination") String destination,
                           @Param("date") LocalDate date,
                           @Param("status") String status,
                           Sort sort);

    /** The same filters, one page at a time. */
    @EntityGraph(attributePaths = {"airline", "origin", "destination"})
    @Query("""
            select f from Flight f
            where (:search is null
                   or lower(f.flightNo) like :search
                   or lower(f.origin.code) like :search
                   or lower(f.destination.code) like :search
                   or lower(f.airline.name) like :search)
              and (:airlineId is null or f.airline.id = :airlineId)
              and (:origin is null or upper(f.origin.code) = :origin)
              and (:destination is null or upper(f.destination.code) = :destination)
              and (:date is null or f.flightDate = :date)
              and (:status is null or f.status = :status)
            """)
    Page<Flight> searchPage(@Param("search") String search,
                            @Param("airlineId") Integer airlineId,
                            @Param("origin") String origin,
                            @Param("destination") String destination,
                            @Param("date") LocalDate date,
                            @Param("status") String status,
                            Pageable pageable);
}
