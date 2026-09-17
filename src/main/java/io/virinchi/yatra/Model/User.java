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

    //Unique pani ho — signup ma email duplicate vayo vane 409 EMAIL_EXISTS dinxa (§31.2).
    @Column(unique = true)
    private String email;

    //Phone pani unique, tara 10-digit national form ma store hunxa: MockDB.normalizePhone() ko rule.
    @Column(unique = true)
    private String phone;

    //BCrypt hash matra — plain password kahile pani save gardaina (Master Plan §3.4).
    private String password;

    //ADMIN / USER — yasle @PreAuthorize("hasRole('ADMIN')") lai chalauxa (Phase 3).
    private String role;

    //Active / Inactive — admin le deactivate garna sakxa (Phase 11).
    private String status;

    private LocalDateTime registeredAt;

    //User 1:N Booking (Roadmap Phase 2). FK booking table ma hunxa, so yo inverse side ho.
    //@JsonIgnore + @ToString/@EqualsAndHashCode.Exclude: bidirectional bhayeko le
    //toString/equals/JSON ma infinite recursion hunxa — tyo रोकna ko lagi.
    @OneToMany(mappedBy = "user")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Booking> bookings = new ArrayList<>();
}
