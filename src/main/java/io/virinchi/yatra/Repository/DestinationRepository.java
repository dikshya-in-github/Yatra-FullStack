package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Destination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DestinationRepository extends JpaRepository<Destination, Integer> {

    //3-letter airport code unique — wizard ko search yahi code pair le search garxa (KTM→PKR).
    boolean existsByCode(String code);
    Optional<Destination> findByCode(String code);

    List<Destination> findByStatus(String status);
    List<Destination> findByCityContainingIgnoreCase(String city);
}
