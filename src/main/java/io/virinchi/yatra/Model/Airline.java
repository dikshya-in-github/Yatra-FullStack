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
    @Lob
    @Column(columnDefinition = "MEDIUMBLOB")
    private String logo;

    //Airline 1:N Flight (Roadmap Phase 2) — FK flight table ma.
    @OneToMany(mappedBy = "airline")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Flight> flights = new ArrayList<>();
}
