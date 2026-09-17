package io.virinchi.yatra.Model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Entity
@Data
public class Airline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String name;

    //2-letter IATA code (U4, YT, S3, ST) — flight number yahi bata bantinxa: "U4 951".
    @Column(unique = true)
    private String iata;

    @Column(length = 1000)
    private String description;

    //Active / Inactive — inactive airline ko flight listing ma aaudaina.
    private String status;

    //Logo database bhitra Base64 String ko roop ma, MEDIUMBLOB column ma
    //(assignment ko requirement: image static file ma hoina, DB ma ho).
    //Teacher ko VirImgTable.image jastai exact pattern.
    //@JsonIgnore: the blob must never reach a response. List/detail JSON carries
    //a URL instead (Dto/AirlineResponse), and the bytes are served by
    //GET /api/airlines/{id}/logo. Serialising this entity directly would inline
    //every Base64 image into the payload — risk R8. Same guard as User.password.
    @Lob
    @Column(columnDefinition = "MEDIUMBLOB")
    @JsonIgnore
    private String logo;

    //Seed marker (Phase 7) — true only for rows the demo seeder created.
    //`POST /api/admin/reset` deletes exactly the seeded rows, so the demo can be
    //re-run without a manual DB cleanup and an admin's own records are never
    //touched. It mirrors the frontend mock's `_seed: true` flag, which is also
    //what makes its trash icon appear on demo rows only.
    @Column(nullable = false)
    private boolean seeded;

    //Airline 1:N Flight — FK flight table ma.
    @OneToMany(mappedBy = "airline")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Flight> flights = new ArrayList<>();
}
