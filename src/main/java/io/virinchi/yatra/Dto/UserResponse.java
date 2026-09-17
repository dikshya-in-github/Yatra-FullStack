package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.User;

import java.time.ZoneId;

/**
 * The public shape of a user — the object {@code auth.js} caches in
 * {@code sessionStorage[YATRA_CONFIG.AUTH_USER_KEY]} and every page's navbar
 * chip reads.
 *
 * <p><b>Field-for-field what {@code api.js}'s {@code publicUser()} returns</b>,
 * including the key {@code userId} (not {@code id}): login.html, signup.html,
 * profile.js, admin-profile.js and the navbar chrome all read {@code u.userId}.
 * Mock and real mode therefore stay interchangeable, which is the whole point of
 * the { token, user } contract.
 *
 * <p><b>There is no {@code password} field, and that is the contract</b> — this
 * record is the boundary that keeps the hash out of every response, rather than
 * relying on remembering not to serialise the entity.
 *
 * <p>{@code registeredAt} is formatted the way the mock emits it: an ISO-8601
 * instant ending in {@code Z}. The column is a zone-less {@code datetime(6)}, so
 * the value is interpreted in the server's zone and then converted — that is the
 * honest reading of a wall-clock timestamp, and it keeps
 * {@code new Date(u.registeredAt)} (profile.js, admin-users.js) unambiguous
 * rather than browser-zone dependent.
 */
public record UserResponse(

        int userId,
        String name,
        String email,
        String phone,
        String role,
        String status,
        String registeredAt
) {

    /** Maps the entity onto the public shape. Never include the password. */
    public static UserResponse of(User user) {
        return new UserResponse(
                user.getId(),
                blankIfNull(user.getName()),
                blankIfNull(user.getEmail()),
                blankIfNull(user.getPhone()),
                user.getRole() == null ? "USER" : user.getRole(),
                user.getStatus() == null ? "Active" : user.getStatus(),
                user.getRegisteredAt() == null
                        ? ""
                        : user.getRegisteredAt()
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toString());
    }

    /**
     * The mock answers {@code ""} — never {@code null} — for an absent value, and
     * page code does things like {@code u.email || ""} and
     * {@code u.registeredAt ? ... : ...}. Keeping {@code ""} here means no page
     * has to distinguish "mock did not send it" from "real API forgot it".
     */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
