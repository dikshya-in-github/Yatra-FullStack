package io.virinchi.yatra.Model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class Destination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String city;

    //3-letter IATA airport code (KTM, PKR, BIR, BWA...) — the wizard's search
    //and the flight route both key on this, so it must stay unique.
    @Column(unique = true)
    private String code;

    private String airport;

    @Column(length = 1000)
    private String description;

    //Destination images Cloudinary ma — yaha URL string matra store hunxa,
    //bytes hoina (Roadmap Phase 8).
    private String imageUrl;
    private String imagePublicId; //Cloudinary delete() ko lagi chahinxa

    //Active / Inactive
    private String status;
}
