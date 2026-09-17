package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Destination;
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
public interface DestinationRepository extends JpaRepository<Destination, Integer> {

    //3-letter airport code unique — wizard ko search yahi code pair le search garxa (KTM→PKR).
    boolean existsByCode(String code);
    Optional<Destination> findByCode(String code);

    //Case-insensitive lookup: `destination.code` is utf8mb4_bin (case-SENSITIVE),
    //so "ktm" would not find "KTM" without this — and the flight module resolves an
    //admin-typed code against this table.
    Optional<Destination> findByCodeIgnoreCase(String code);

    //The admin page's second duplicate rule: two rows for the same city would show
    //up twice in the wizard's arrival list. Unlike `code` there is no DB constraint
    //behind it, so the service check is the only guard (see DestinationService).
    Optional<Destination> findByCityIgnoreCase(String city);

    List<Destination> findByStatus(String status);
    List<Destination> findByCityContainingIgnoreCase(String city);

    /**
     * The destination list: one search box over city, airport and code, plus an
     * independent status filter.
     *
     * <p><b>One nullable-parameter query rather than four derived ones.</b> The
     * page's search box matches any of three columns, and combining that with the
     * status dropdown as derived methods would need a separate signature per
     * combination — so each clause is {@code :param is null or ...} and the
     * service passes null for "no filter", the same shape
     * {@code FlightRepository.searchAll} uses.
     *
     * <p><b>{@code like(...)} is built by the service, not by this query.</b> The
     * caller passes the already-lowercased, already-wildcarded pattern, because a
     * {@code lower(...) like :search} with a bare value would only match an exact
     * whole-string value — the wildcards are the caller's job and are visible
     * there.
     *
     * <p><b>Its own query, not {@code Pageable.unpaged()}.</b> Paging without an
     * {@code ORDER BY} lets a row appear on two pages or none (InnoDB/TiDB promise
     * no order), and {@code unpaged()} silently discards the {@code Sort} — the
     * R6 lesson. Hence the separate {@code Sort}-taking list query below.
     */
    @Query("""
            select d from Destination d
            where (:search is null
                   or lower(d.city) like :search
                   or lower(d.airport) like :search
                   or lower(d.code) like :search)
              and (:status is null or d.status = :status)
            """)
    List<Destination> searchAll(@Param("search") String search,
                                @Param("status") String status,
                                Sort sort);

    /** The same filters, one page at a time. */
    @Query("""
            select d from Destination d
            where (:search is null
                   or lower(d.city) like :search
                   or lower(d.airport) like :search
                   or lower(d.code) like :search)
              and (:status is null or d.status = :status)
            """)
    Page<Destination> searchPage(@Param("search") String search,
                                 @Param("status") String status,
                                 Pageable pageable);
}
