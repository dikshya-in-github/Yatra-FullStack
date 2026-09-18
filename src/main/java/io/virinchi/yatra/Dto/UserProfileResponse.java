package io.virinchi.yatra.Dto;

/**
 * The body of {@code GET}, {@code POST} and {@code PUT /api/users/me} —
 * <pre>{ "user": { … } }</pre>
 *
 * <h2>Why the customer surface gets its own envelope record</h2>
 * <p>{@link AdminProfileResponse} already wraps {@link UserResponse}, and the two
 * records are the same nine lines. They are still separate on purpose: that one is
 * <i>documented as the body of the admin endpoint</i>, reached through
 * {@code /api/admin/profile} where {@code SecurityConfig} and
 * {@code @PreAuthorize("hasRole('ADMIN')")} both refuse first. Reusing it here would
 * have made an admin-named contract the answer to a customer route — the sort of
 * naming drift that reads as a copy-paste long after the reason is gone.
 *
 * <p><b>The nested type is the shared half, and that is the part that matters.</b>
 * Both envelopes carry {@link UserResponse}, which is what {@code api.js}'s
 * {@code publicUser()} returns and what the pages actually read: the key is
 * {@code userId}, never {@code id}, plus {@code name}, {@code email}, {@code phone},
 * {@code role}, {@code status} and {@code registeredAt}. Mock and real mode stay
 * interchangeable because the inner shape is shared, not because the wrapper is — so
 * the wrapper is free to be named for its surface.
 *
 * <h2>The envelope is the contract, not decoration</h2>
 * <p>{@code profile.js} reads {@code res.user} on both its calls — the load
 * ({@code apiGet('/api/users/me').then(res => { user = res.user; … })}) and the save
 * ({@code res.user}, then {@code YatraAuth.updateCurrentUser(user)}). {@code api.js}'s
 * mock route answers {@code { user: publicUser(record) }} for the same path, so the
 * real endpoint has to answer the same envelope or the page would render an empty
 * profile and cache an {@code undefined} session — a failure with no error on it,
 * which is risk R16 on one more surface.
 *
 * <p>{@link UserResponse#of} is the boundary that keeps the BCrypt hash out of the
 * body: neither record has a password field, and none is added here.
 */
public record UserProfileResponse(UserResponse user) {

    /**
     * Wraps the account the service already mapped.
     *
     * <p>Takes the {@link UserResponse} rather than the {@code User} entity because the
     * service is where the mapping belongs — {@code UserService.getOwnProfile} and
     * {@code updateOwnProfile} both convert through {@link UserResponse#of} on the way
     * out. Re-mapping here would be a second answer to what a user looks like over the
     * wire.
     */
    public static UserProfileResponse of(UserResponse user) {
        return new UserProfileResponse(user);
    }
}
