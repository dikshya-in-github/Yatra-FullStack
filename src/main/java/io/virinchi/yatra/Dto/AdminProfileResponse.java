package io.virinchi.yatra.Dto;

/**
 * The body of {@code GET} and {@code POST /api/admin/profile} —
 * <pre>{ "user": { … } }</pre>
 *
 * <h2>The envelope is the contract, not decoration</h2>
 * <p>{@code admin-profile.js} reads {@code res.user} on both calls — the load path
 * ({@code apiGet('/api/admin/profile').then(res => { user = res.user; … })}) and the
 * save path ({@code res.user} again, then {@code saveAdminSession(user)}). The mock
 * answers {@code { user: publicUser(record) }} in both cases, so the real endpoint
 * has to answer the same envelope or the page would render an empty profile and store
 * an undefined admin session — a failure with no error on it, which is risk R16 on
 * one more surface.
 *
 * <h2>Why the nested record is {@link UserResponse}</h2>
 * <p>Because {@code publicUser()} is the shape the page already holds: the key is
 * {@code userId}, <b>not</b> {@code id} (that distinction is
 * {@link AdminUserResponse}'s whole story on the roster surface), and it carries
 * {@code name}, {@code email}, {@code phone}, {@code role}, {@code status} and
 * {@code registeredAt}. Reusing it rather than adding a profile-specific record keeps
 * the sign-in response ({@code AuthResponse.user}), the session cache
 * ({@code yatra_admin_session}) and this endpoint speaking one vocabulary — which is
 * also why the session the page re-saves after a save is interchangeable with the one
 * sign-in produced.
 *
 * <p>{@link UserResponse#of} is the boundary that keeps the BCrypt hash out of the
 * body; there is no password field on either record, and none is added here.
 */
public record AdminProfileResponse(UserResponse user) {

    /**
     * Wraps the account the service already mapped.
     *
     * <p>Takes the {@link UserResponse} rather than the {@code User} entity because the
     * service is where the mapping belongs — {@code UserService.getOwnProfile} and
     * {@code updateOwnProfile} both convert through {@link UserResponse#of} on the way
     * out, which is the one place that decides what a user looks like over the wire.
     * Re-mapping here would be a second answer to that question.
     */
    public static AdminProfileResponse of(UserResponse user) {
        return new AdminProfileResponse(user);
    }
}
