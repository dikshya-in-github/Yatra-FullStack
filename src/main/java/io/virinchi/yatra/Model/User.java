package io.virinchi.yatra.Model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users") //not "user" — that is a MySQL keyword, so the table gets its plural name
@Data                   //lombok ko ho, getter setter toString equals sabai dinxa
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String name;

    //Unique pani ho — signup ma email duplicate vayo vane 409 EMAIL_EXISTS dinxa.
    @Column(unique = true)
    private String email;

    //Phone pani unique, tara 10-digit national form ma store hunxa: MockDB.normalizePhone() ko rule.
    @Column(unique = true)
    private String phone;

    //BCrypt hash matra — plain password kahile pani save gardaina.
    //@JsonIgnore: defence in depth. This entity must never be serialised to a
    //response directly — @see io.virinchi.yatra.Dto.UserResponse is the public
    //shape. If a future controller ever returns the entity by mistake, the hash
    //still cannot leave the server.
    @JsonIgnore
    private String password;

    //ADMIN / USER — yasle @PreAuthorize("hasRole('ADMIN')") lai chalauxa.
    private String role;

    //Active / Inactive — admin le deactivate garna sakxa.
    private String status;

    private LocalDateTime registeredAt;

    //Seed marker (Phase 7) — true only for the demo roster the seeder created.
    //A seeded account carries a BCrypt hash of the demo password, so the panel can
    //be signed into on a freshly seeded database; a reset removes these accounts
    //unless one of them still owns a booking you made yourself.
    //(See Model/Airline for the full marker note.)
    @Column(nullable = false)
    private boolean seeded;

    //User 1:N Booking. FK booking table ma hunxa, so yo inverse side ho.
    //@JsonIgnore + @ToString/@EqualsAndHashCode.Exclude: bidirectional bhayeko le
    //toString/equals/JSON ma infinite recursion hunxa — tyo रोकna ko lagi.
    @OneToMany(mappedBy = "user")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Booking> bookings = new ArrayList<>();
}
