package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
