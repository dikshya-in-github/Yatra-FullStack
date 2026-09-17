package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository //Communicates with the MODEL table for CRUD Operations
public interface UserRepository extends JpaRepository<User, Integer> {

    //Login/register ma email ra mobile duitai identifier ho, so duitai unique check chahinxa.
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);

    //Login: email case-insensitive hunaiparxa (frontend ko findUser() pani yahi garthe),
    //moblie chai normalize bhayeko 10-digit form ma aauxa.
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByPhone(String phone);

    //The demo seeder's own roster (Phase 7) — what `POST /api/admin/reset` removes.
    List<User> findBySeededTrue();

    //Admin user management — search + status filter.
    List<User> findByStatus(String status);
    List<User> findByNameContainingIgnoreCase(String name);
    Page<User> findByStatus(String status, Pageable pageable);

    /**
     * Uniqueness on edit, excluding the row being edited.
     *
     * <p>{@code existsByEmail} answers the wrong question on an update: the account
     * being saved already owns its email, so a plain existence check would refuse the
     * edit of a user who did not change their address at all — and the admin panel's
     * modal posts every field on every save. These are the two checks
     * {@code UserService.updateUser} needs, and they are check-then-throw for the same
     * reason {@code register} is: the 409 has to carry {@code EMAIL_EXISTS} /
     * {@code PHONE_EXISTS}, which the profile pages already paint onto the offending
     * field and which the database's generic duplicate-key error cannot name.
     *
     * <p>A {@code null} identifier simply matches nothing (SQL equality against
     * {@code null} is never true), which is the behaviour wanted here — the service
     * skips the check outright when there is nothing to check.
     */
    boolean existsByEmailAndIdNot(String email, int id);

    boolean existsByPhoneAndIdNot(String phone, int id);

    /**
     * The admin user list — Roadmap Phase 11.
     *
     * <p><b>One nullable-parameter query, not eight derived ones.</b> Three optional
     * filters would be eight combinations and the next filter doubles that, so every
     * clause is {@code :param is null or ...} and the service passes {@code null} for
     * "no filter" — the same shape {@code FlightRepository.searchAll} and
     * {@code BookingRepository.searchAll} use, for the same reason.
     *
     * <p><b>The id match is OR'd with the text matches, not AND'd.</b> That clause
     * mirrors {@code BookingRepository}'s, and the lesson was learned there: the
     * service sets both parameters when the term is numeric, so an {@code and} would
     * demand a row whose name matches <i>and</i> whose id matches, and searching for
     * the id the list just handed back would return nothing.
     *
     * <p><b>The case handling differs per column because the storage does.</b>
     * {@code role} is stored upper-case ({@code ADMIN}) and {@code status} title-case
     * ({@code Active}) — both exactly as {@code MockDB.SEED_USERS} spells them — so the
     * query upper-cases the one and lower-cases the other and the service normalises
     * the incoming value to match. Without it, {@code status=active} would silently
     * match nothing and the page would show an empty table.
     *
     * <p><b>Its own query, not {@code Pageable.unpaged()}.</b> Unpaged discards the
     * {@code Sort} (the R6 lesson), so the list path keeps an explicitly ordered query
     * of its own. No {@code @EntityGraph}: the roster response reads only columns on
     * the {@code users} row, and the one association ({@code bookings}) is never
     * touched by it.
     */
    @Query("""
            select u from User u
            where ((:search is null and :idSearch is null)
                   or lower(u.name) like :search
                   or lower(u.email) like :search
                   or lower(u.phone) like :search
                   or (:idSearch is not null and u.id = :idSearch))
              and (:role is null or upper(u.role) = :role)
              and (:status is null or lower(u.status) = :status)
            """)
    List<User> searchAll(@Param("search") String search,
                         @Param("idSearch") Integer idSearch,
                         @Param("role") String role,
                         @Param("status") String status,
                         Sort sort);

    /** The same filters, one page at a time. */
    @Query("""
            select u from User u
            where ((:search is null and :idSearch is null)
                   or lower(u.name) like :search
                   or lower(u.email) like :search
                   or lower(u.phone) like :search
                   or (:idSearch is not null and u.id = :idSearch))
              and (:role is null or upper(u.role) = :role)
              and (:status is null or lower(u.status) = :status)
            """)
    Page<User> searchPage(@Param("search") String search,
                          @Param("idSearch") Integer idSearch,
                          @Param("role") String role,
                          @Param("status") String status,
                          Pageable pageable);
}
