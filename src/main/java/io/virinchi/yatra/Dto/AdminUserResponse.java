package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.User;

import java.time.ZoneId;

/**
 * One user as {@code admin-users.html} sees it — the mock roster's record shape,
 * transcribed key by key from {@code assets/js/admin-users.js} and
 * {@code MockDB.SEED_USERS}.
 *
 * <h2>Why this is a second record beside {@link UserResponse}</h2>
 * <p>Because the two shapes <b>disagree on the id key</b>, and the disagreement is
 * real rather than cosmetic. {@code UserResponse} (the signed-in user, cached by
 * {@code auth.js}) emits {@code userId}, which is what the navbar chip and
 * profile.js read. The admin roster in {@code mock-data.js} is a plain row with an
 * {@code id}, and {@code admin-users.js} builds every row's actions from it:
 * {@code data-edit="${u.id}"}, {@code data-toggle="${u.id}"},
 * {@code users.find(x => String(x.id) === id)} and {@code String(u.id).includes(q)}
 * in the search box.
 *
 * <p><b>Emitting {@code userId} here would fail silently, which is why it is
 * called out.</b> The page's {@code normalize()} would repair the missing key with
 * {@code u.id = Date.now()} — so rows would render, the search box would work, and
 * the edit/toggle/delete buttons would look fine until they sent
 * {@code PUT /api/admin/users/1758…} and got a 404. Every symptom would point at
 * the backend while the cause was one key name. Same lesson as R16 for the payments
 * ledger, on a new surface: <b>the page's own vocabulary is the contract.</b>
 *
 * <p>{@code id} is a plain number here, not the string {@code AdminBookingResponse}
 * emits: {@code admin-bookings.js} calls {@code b.id.toLowerCase()}, so that one
 * genuinely needs text, while this page only ever stringifies the id itself
 * ({@code String(u.id)}) and interpolates it into an attribute. A string id would
 * work too; a number is simply the honest type for a numeric key.
 *
 * <h2>Every other key is a non-null string, exactly like the mock</h2>
 * <p>{@code normalize()} repairs a falsy {@code name}/{@code email}/{@code role}/
 * {@code status}/{@code registeredAt} before rendering, so a null would in practice
 * be papered over — but the repairs are page-side defaults ({@code 'Unnamed'},
 * {@code '—'}) that would <i>hide</i> a missing value. The factories below answer
 * {@code ""} for an absent value, the same call {@code UserResponse} and
 * {@code FlightResponse} make.
 *
 * <h2>One key is an addition, and it is safe</h2>
 * <p>{@code canSignIn} is not in the mock. It is here because this phase creates the
 * only accounts in the system that can <i>have no credential at all</i> (see
 * {@link AdminUserRequest}) — the page cannot yet show it, and when it can, "this
 * account cannot sign in and its email is already taken" is the one thing an admin
 * needs to see before the person tries to register. Extra keys break nothing: every
 * page reads named fields.
 */
public record AdminUserResponse(

        /** The roster's own key — {@code userId} would break the row actions. */
        int id,

        String name,
        String email,
        String phone,

        /** {@code ADMIN} / {@code USER}, upper-case, as stored and as the page's badges expect. */
        String role,

        /** {@code Active} / {@code Inactive}, title-case, as stored and as the page's filter sends. */
        String status,

        /** ISO-8601 instant ending in {@code Z}, the format {@code fmtDate()} parses. */
        String registeredAt,

        /**
         * True only when this account actually holds a BCrypt hash <b>and</b> is
         * {@code Active} — i.e. when {@code POST /api/auth/login} would succeed for
         * it. An admin-created account with no password, or a deactivated one, is
         * {@code false}.
         */
        boolean canSignIn
) {

    /** Maps the entity onto the roster shape. Never include the password. */
    public static AdminUserResponse of(User user) {
        boolean active = "Active".equalsIgnoreCase(String.valueOf(user.getStatus()));

        return new AdminUserResponse(
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
                                .toString(),
                active && hasCredential(user));
    }

    /**
     * Whether a credential exists. A blank hash is the same as none: both make
     * {@code UserService.authenticate} refuse, and the panel's created accounts are
     * stored as {@code NULL} (the column is nullable) rather than as an empty
     * string.
     */
    private static boolean hasCredential(User user) {
        String stored = user.getPassword();
        return stored != null && !stored.isBlank();
    }

    /** The mock answers {@code ""}, never {@code null}, for an absent value. */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
