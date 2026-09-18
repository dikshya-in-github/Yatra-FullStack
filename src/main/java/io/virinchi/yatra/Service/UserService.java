package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.AdminUserRequest;
import io.virinchi.yatra.Dto.AdminUserResponse;
import io.virinchi.yatra.Dto.ProfileUpdateRequest;
import io.virinchi.yatra.Dto.RegisterRequest;
import io.virinchi.yatra.Dto.UserResponse;
import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.DuplicateResourceException;
import io.virinchi.yatra.Exception.ForbiddenException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Exception.UnauthorizedException;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Security.Authorities;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Account creation and sign-in — the data half of auth, with no knowledge of
 * JWT or HTTP.
 *
 * <p>Also the project's {@link UserDetailsService}: Spring Security's contract
 * needs one to exist, and {@link Authorities} is the single role conversion it
 * must go through.
 *
 * <h2>Two rules that are easy to get subtly wrong</h2>
 *
 * <p><b>1. Blank means {@code null} in the database, never {@code ""}.</b> Both
 * unique indexes are real, and MySQL/TiDB allow repeated {@code NULL}s in a
 * unique index but treat {@code ""} as a value. Storing the empty string for a
 * user who signed up with only a mobile number would make the <i>second</i> such
 * user fail with a bogus {@code 409 PHONE_EXISTS} — a bug that only appears once
 * two people register the same way.
 *
 * <p><b>2. The email is lower-cased before it is stored.</b> {@code users.email}
 * is {@code COLLATE=utf8mb4_bin}, i.e. case-<b>sensitive</b>, so the database
 * would happily accept {@code A@x.com} and {@code a@x.com} as two accounts.
 * Normalising on the way in is what makes "case-insensitive" true at the storage
 * layer rather than only in the lookup. Trade-off, stated plainly: the stored
 * value is not byte-identical to what was typed; the display name is unaffected,
 * and login stays case-insensitive either way.
 *
 * <p>{@code register} and {@code authenticate} return the entity so the
 * controller can shape the response ({@code Dto/UserResponse} is the public
 * shape, and {@code User.password} carries {@code @JsonIgnore} as a second
 * line of defence).
 *
 * <h2>Phase 11 adds three things to this class, and each one closes a gap</h2>
 *
 * <p><b>1. The admin roster lives here too</b> (list, search, filter, page, create,
 * edit, activate/deactivate, delete). It is the same domain — the same table, the same
 * two unique indexes, the same email lower-casing and {@code +977} phone normalising
 * that {@code register} already owns — so putting it in a second service would mean a
 * second copy of those rules, which is exactly how a roster and a signup path drift
 * apart. The admin <i>controller</i> is separate ({@code AdminUserController}), which is
 * where the role check belongs.
 *
 * <p><b>2. {@code authenticate} now refuses an {@code Inactive} account.</b> It did not
 * before, and that was a real bug rather than a missing feature: the column was only
 * consulted by {@code loadUserByUsername} (the Spring Security path), while the real
 * login flow calls {@code authenticate} directly — so deactivating somebody through the
 * admin panel did nothing at all. The refusal is a 403 with its own code, not a 401:
 * the password was right, and telling the user otherwise sends them round a login loop
 * they cannot fix.
 *
 * <p><b>3. {@code register} sends the confirmation email</b>, through
 * {@link MailService}, after the account is saved. Best effort by design — see that
 * class for why a mail failure must never fail a registration, and why the send is
 * switched off in the test suite rather than mocked away.
 */
@Service
@Slf4j
public class UserService implements UserDetailsService {

    /** The two role values the schema and the panel know, and no others. */
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";

    /** The stored spelling of a status, exactly as {@code MockDB.SEED_USERS} writes it. */
    private static final String STATUS_ACTIVE = "Active";
    private static final String STATUS_INACTIVE = "Inactive";

    /**
     * The sort properties a caller may ask for, keyed by the name the <b>page</b> uses —
     * anything not listed falls back to {@code id}, so no request can order by a column
     * that does not exist (R6). The page has no sort control today; these exist so a
     * Postman caller and the coming server-side paging have something defined to use.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "name", "name",
            "email", "email",
            "role", "role",
            "status", "status",
            "registered", "registeredAt");

    private static final String DEFAULT_SORT = "id";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;

    /**
     * The bookings that reference a user — read by {@code deleteUser} only, to refuse a
     * delete that the {@code booking.user_id} foreign key would refuse anyway (see the
     * guard's own note). The repository, not {@code BookingService}, because this is a
     * count of child rows and nothing about the booking lifecycle is involved.
     */
    private final BookingRepository bookings;

    /**
     * A real BCrypt hash, compared against when the identifier does not exist so
     * that "no such account" costs the same as "wrong password". Without it the
     * not-found path returns in a few milliseconds while a real compare takes
     * ~100ms of BCrypt — a difference large enough to enumerate registered
     * emails with. Computed once at startup, not per request.
     */
    private final String timingDefenceHash;

    /*
     * Written out rather than @RequiredArgsConstructor: the last field needs the
     * encoder to produce a hash, which Lombok's generated constructor cannot do.
     */
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       BookingRepository bookings, MailService mailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.bookings = bookings;
        this.mailService = mailService;
        this.timingDefenceHash = passwordEncoder.encode("yatra-timing-defence-not-a-credential");
    }

    /* ------------------------------------------------------------------ *
     *  Registration                                                       *
     * ------------------------------------------------------------------ */

    /**
     * Creates an {@code Active}/{@code USER} account with a BCrypt hash.
     *
     * @throws ValidationException       no email and no mobile number, or a
     *                                   mobile number that is not 10 digits
     * @throws DuplicateResourceException 409 {@code EMAIL_EXISTS} /
     *                                   {@code PHONE_EXISTS} — the codes the
     *                                   frontend already paints onto the field
     */
    @Transactional
    public User register(RegisterRequest request) {
        String email = blankToNull(request.email());
        String phone = blankToNull(normalizePhone(request.phone()));

        if (email == null && phone == null) {
            throw new ValidationException("Provide an email address or a mobile number.");
        }

        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (userRepository.existsByEmail(email)) {
                throw DuplicateResourceException.emailExists();
            }
        }

        if (phone != null) {
            if (!phone.matches("\\d{10}")) {
                throw new ValidationException("Enter a 10-digit mobile number.");
            }
            if (userRepository.existsByPhone(phone)) {
                throw DuplicateResourceException.phoneExists();
            }
        }

        User user = new User();
        user.setName(fullName(request));
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(ROLE_USER);
        user.setStatus(STATUS_ACTIVE);
        user.setRegisteredAt(LocalDateTime.now());

        User saved = userRepository.save(user);

        // After the row exists, because the message names the account that was
        // created. Best effort by contract: MailService logs its failures and
        // returns false rather than throwing, so an unreachable SMTP server cannot
        // undo a registration that has already happened. Phase 11.
        mailService.sendRegistrationConfirmation(saved);

        return saved;
    }

    /* ------------------------------------------------------------------ *
     *  Sign-in                                                            *
     * ------------------------------------------------------------------ */

    /**
     * Verifies an identifier (email or mobile) and a password, then that the account
     * is allowed to sign in at all.
     *
     * <p>Both <i>credential</i> failure modes throw the <b>same</b>
     * {@code 401 INVALID_CREDENTIALS} — a caller must not be able to tell "no such
     * account" from "wrong password", which is also why the not-found branch still
     * performs a BCrypt compare.
     *
     * <p><b>The {@code Inactive} check runs after the password check, in that order on
     * purpose.</b> Doing it first would answer "this account is deactivated" to somebody
     * who does not know the password — an account-is-here oracle for anyone who can
     * guess an email address. After a successful compare the caller has already proved
     * they own the account, so answering "you are deactivated" tells them the one thing
     * they actually need and leaks nothing.
     *
     * <p><b>Until Phase 11 this check did not exist anywhere on this path</b>, which made
     * the admin panel's deactivate button cosmetic: {@code status} was read by
     * {@link #loadUserByUsername} only, and login never goes through Spring Security's
     * provider (the filter exists to validate a token, not to sign anybody in).
     *
     * <p>Also worth knowing: an admin-created account with no password fails the
     * {@code stored == null} branch above. That is the honest answer — there is no
     * credential to match — and it is why {@link AdminUserResponse#canSignIn()} exists.
     *
     * @throws UnauthorizedException 401 {@code INVALID_CREDENTIALS}
     * @throws ForbiddenException    403 {@code ACCOUNT_DISABLED}
     */
    @Transactional(readOnly = true)
    public User authenticate(String loginId, String rawPassword) {
        Optional<User> found = findByIdentifier(loginId);
        String password = String.valueOf(rawPassword == null ? "" : rawPassword);

        if (found.isEmpty()) {
            passwordEncoder.matches(password, timingDefenceHash);
            //The roadmap's "auth failures" event (Phase 14). The login id is logged on
            //purpose — "someone tried to sign in" is not actionable, "someone tried to
            //sign in as admin@gmail.com" is — and the password never is, on any path.
            //This line is not redundant with GlobalExceptionHandler's: that one records
            //what the caller was told (a generic INVALID_CREDENTIALS), this one records
            //which account was attempted, which is exactly what must not leak outward.
            log.warn("Sign-in refused: no account matches the login id {}", loginId);
            throw UnauthorizedException.invalidCredentials();
        }

        User user = found.get();
        String stored = user.getPassword();
        if (stored == null || stored.isBlank()
                || !passwordEncoder.matches(password, stored)) {
            //An account that exists but has no usable credential reaches here too —
            //the state an admin-created account is in until a password is provisioned.
            log.warn("Sign-in refused: wrong password (or no credential on file) for {}",
                    user.getEmail());
            throw UnauthorizedException.invalidCredentials();
        }

        if (!isActive(user)) {
            throw ForbiddenException.accountDisabled();
        }

        return user;
    }

    /* ------------------------------------------------------------------ *
     *  Admin user management (Roadmap Phase 11)                            *
     * ------------------------------------------------------------------ */

    /**
     * The roster, every matching account, in a deterministic order — the admin
     * table's read.
     *
     * <p><b>Unpaged by default, paged on request</b> ({@link #listUserPage}), the same
     * choice every other admin list makes: {@code admin-users.js} filters and pages its
     * own eight rows client-side today, so a silent server-side page size would look
     * like missing users on a page that has no idea it was truncated.
     *
     * <p><b>What each filter means.</b> {@code search} spans the name, email and mobile
     * number — what the page's search box promises — and a purely numeric term is
     * matched against the id as an alternative (see
     * {@link #idTerm(String)}). {@code role} and {@code status} take {@code ALL} or
     * blank for "no filter", because the page's two dropdowns send exactly that.
     *
     * @param role   {@code ADMIN} / {@code USER}, any case, or {@code ALL}/blank
     * @param status {@code Active} / {@code Inactive}, any case, or {@code ALL}/blank
     * @param sort   one of {@code id|name|email|role|status|registered}; anything else
     *               falls back to {@code id}
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers(String search, String role, String status, String sort) {
        return userRepository.searchAll(
                        like(search), idTerm(search), storedRole(role), storedStatus(status), sortFor(sort))
                .stream()
                .map(AdminUserResponse::of)
                .toList();
    }

    /** One page of matching accounts, plus the counts needed to walk the rest. */
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> listUserPage(String search, String role, String status, String sort,
                                                int page, int size) {
        PageRequest request = Paging.request(page, size, sortFor(sort));

        return userRepository.searchPage(
                        like(search), idTerm(search), storedRole(role), storedStatus(status), request)
                .map(AdminUserResponse::of);
    }

    /**
     * One account, in the roster's shape.
     *
     * @throws ResourceNotFoundException 404 {@code USER_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public AdminUserResponse getUser(int userId) {
        return AdminUserResponse.of(require(userId));
    }

    /* ------------------------------------------------------------------ *
     *  The administrator's own account (admin-profile.html)               *
     * ------------------------------------------------------------------ */

    /**
     * The signed-in administrator's own account, in the session shape.
     *
     * <p><b>Why {@link UserResponse} and not {@link AdminUserResponse}.</b> The two
     * records disagree on the id key on purpose — the roster row is {@code id} (what
     * {@code admin-users.js}'s row actions are built from) while the session is
     * {@code userId} (what {@code admin-profile.js}, {@code auth.js} and the navbar
     * chip read). This is the profile page, so it takes the session shape; that is
     * also what makes the record it re-saves into {@code yatra_admin_session} identical
     * to the one sign-in produced.
     *
     * <p>The caller is resolved from the JWT's {@code sub} claim by the controller, so
     * there is no id in the URL and an administrator can only ever read their own row.
     *
     * @throws ResourceNotFoundException 404 {@code USER_NOT_FOUND} — a token whose
     *         account has since been deleted, which is a stale session rather than a
     *         reachable page state
     */
    @Transactional(readOnly = true)
    public UserResponse getOwnProfile(int userId) {
        return UserResponse.of(require(userId));
    }

    /**
     * Edits the signed-in administrator's own name, email and mobile number.
     *
     * <h2>Three fields, and nothing else is reachable</h2>
     * <p>{@link ProfileUpdateRequest} simply has no {@code role} or {@code status}
     * field, so a caller cannot promote themselves or reactivate a disabled account by
     * adding a key to the body — the mock's own rule ("only the fields a profile form
     * owns are writable — never role, status or the id") made structural rather than
     * remembered. Nothing is written but {@code name}, {@code email} and {@code phone},
     * so the {@code ADMIN_ACCOUNT_PROTECTED} reasoning that governs
     * {@link #updateUser} is not bypassed here: there is no transition to refuse.
     *
     * <h2>The same uniqueness rules as every other account write, self-excluded</h2>
     * <p>Lower-cased email, {@code +977}-stripped 10-digit phone, and the frontend's own
     * {@code EMAIL_EXISTS} / {@code PHONE_EXISTS} codes — the codes
     * {@code admin-profile.js} paints onto the offending field. The checks use
     * {@code existsByEmailAndIdNot} / {@code existsByPhoneAndIdNot} because a profile
     * form resubmits the address it already had, and a plain existence check would make
     * saving an unchanged form a 409.
     *
     * <h2>Blank means unchanged</h2>
     * <p>A field left out or sent blank keeps its stored value — never cleared. That is
     * the convention {@link AdminUserRequest} documents for the roster's edit, and it is
     * what makes it safe for this endpoint to have no "at least one identifier" rule:
     * an update can never remove an account's last way to sign in.
     *
     * @throws ResourceNotFoundException  404 {@code USER_NOT_FOUND}
     * @throws DuplicateResourceException 409 {@code EMAIL_EXISTS} / {@code PHONE_EXISTS}
     * @throws ValidationException        a mobile number that is not 10 digits after
     *                                   normalising
     */
    @Transactional
    public UserResponse updateOwnProfile(int userId, ProfileUpdateRequest request) {
        User user = require(userId);

        String email = blankToNull(request.email());
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (!email.equals(user.getEmail())
                    && userRepository.existsByEmailAndIdNot(email, userId)) {
                throw DuplicateResourceException.emailExists();
            }
            user.setEmail(email);
        }

        String phone = blankToNull(normalizePhone(request.phone()));
        if (phone != null) {
            if (!phone.matches("\\d{10}")) {
                throw new ValidationException("Enter a 10-digit mobile number.");
            }
            if (!phone.equals(user.getPhone())
                    && userRepository.existsByPhoneAndIdNot(phone, userId)) {
                throw DuplicateResourceException.phoneExists();
            }
            user.setPhone(phone);
        }

        // @NotBlank on the record, so the page's own "name is required" rule is enforced
        // before this runs; the trim is for a hand-written caller with padded whitespace.
        user.setName(request.name().trim());

        User saved = userRepository.save(user);

        // The roadmap's "auth failures" sibling event on this surface: a profile edit
        // changes what a sign-in resolves to, so it is worth a line. No email or phone in
        // it — the id identifies the row, and the contact details are the personal data
        // this project keeps out of logs (standards doc, logging rule).
        log.info("Admin profile updated: id={} (name/email/phone only; role and status are not "
                + "editable from this endpoint)", saved.getId());

        return UserResponse.of(saved);
    }

    /**
     * Creates an account from the admin panel.
     *
     * <h2>What it does not have: a password field in the UI</h2>
     * <p>{@code admin-users.js} has no password input on purpose — its header says
     * passwords belong to the backend's BCrypt and are never stored or displayed in the
     * panel — so the account this creates <b>has no credential</b> until one is supplied.
     * That is a legitimate state (a roster record for somebody who is not signing in
     * themselves), but it is also a trap, and the trap is worth naming: the record has
     * already taken the email and mobile number, so the same person cannot register
     * either. {@link AdminUserRequest#password()} is the way out of it — send one and the
     * account can sign in like any other; {@link AdminUserResponse#canSignIn()} says which
     * of the two states a row is in.
     *
     * <h2>Uniqueness and the identifier rule are the <i>same</i> ones as register</h2>
     * <p>Lower-cased email, {@code +977}-stripped 10-digit phone, at least one of the two,
     * check-then-throw with the frontend's own {@code EMAIL_EXISTS} / {@code PHONE_EXISTS}
     * codes. This is deliberate duplication of the <i>rules</i>, not of the code: it is the
     * one reason this surface lives in this class rather than a second service, where a
     * second copy would eventually drift.
     *
     * @throws ValidationException        no email and no mobile number, a mobile number
     *                                    that is not 10 digits, or an unknown role/status
     * @throws DuplicateResourceException 409 {@code EMAIL_EXISTS} / {@code PHONE_EXISTS}
     * @throws ConflictException          409 {@code ADMIN_ACCOUNT_PROTECTED} when the
     *                                    request asks for an <b>Inactive ADMIN</b> — an
     *                                    administrator who cannot reach the panel is a
     *                                    contradiction, and every other admin rule below
     *                                    ("an admin may never be deactivated") depends on
     *                                    no such row existing
     */
    @Transactional
    public AdminUserResponse createUser(AdminUserRequest request) {
        String email = blankToNull(request.email());
        String phone = blankToNull(normalizePhone(request.phone()));

        if (email == null && phone == null) {
            throw new ValidationException("Provide an email address or a mobile number.");
        }
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (userRepository.existsByEmail(email)) {
                throw DuplicateResourceException.emailExists();
            }
        }
        if (phone != null) {
            if (!phone.matches("\\d{10}")) {
                throw new ValidationException("Enter a 10-digit mobile number.");
            }
            if (userRepository.existsByPhone(phone)) {
                throw DuplicateResourceException.phoneExists();
            }
        }

        String name = request.name().trim();
        String role = roleOf(request.role());
        String status = statusOf(request.status());

        if (ROLE_ADMIN.equals(role) && !STATUS_ACTIVE.equals(status)) {
            throw ConflictException.adminAccountProtected(name, "created as " + status);
        }

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordOrNull(request.password()));
        user.setRole(role);
        user.setStatus(status);
        user.setRegisteredAt(LocalDateTime.now());
        // seeded stays false (the column's default): this is an admin's own record, not
        // part of the demo roster, so `POST /api/admin/reset` must leave it alone (R14).

        return AdminUserResponse.of(userRepository.save(user));
    }

    /**
     * Edits an account.
     *
     * <h2>Blank means unchanged, and that is why there is no "at least one" check here</h2>
     * <p>The panel submits every field on every save, so a blank field arrives only from a
     * hand-written caller — and reading blank as "clear it" would let a typo in a Postman
     * body wipe a mobile number. Because blank is "leave it alone", an update can never
     * remove a row's last identifier either, so unlike {@code register} and
     * {@code createUser} this method needs no cross-field rule.
     *
     * <h2>What an ADMIN account will and will not accept</h2>
     * <p>Name, email, mobile and password are editable on every account — the panel's own
     * header calls admin accounts "editable, never disabled or deleted", and an admin is
     * the only thing that can ever give a credential to a password-less admin row. The
     * <b>role and status of an ADMIN may not change</b>: demoting the last administrator
     * would leave nobody able to open the panel, and no API call exists that could undo
     * it. Echoing the current values back — which is what the edit modal does, having
     * loaded them — is a no-op rather than a conflict.
     *
     * @throws ResourceNotFoundException  404 {@code USER_NOT_FOUND}
     * @throws DuplicateResourceException 409 {@code EMAIL_EXISTS} / {@code PHONE_EXISTS}
     *                                    when another account already owns the value
     * @throws ConflictException          409 {@code ADMIN_ACCOUNT_PROTECTED} on a real
     *                                    change to an administrator's role or status
     */
    @Transactional
    public AdminUserResponse updateUser(int userId, AdminUserRequest request) {
        User user = require(userId);
        String name = request.name().trim();

        String email = blankToNull(request.email());
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (!email.equals(user.getEmail())
                    && userRepository.existsByEmailAndIdNot(email, userId)) {
                throw DuplicateResourceException.emailExists();
            }
            user.setEmail(email);
        }

        String phone = blankToNull(normalizePhone(request.phone()));
        if (phone != null) {
            if (!phone.matches("\\d{10}")) {
                throw new ValidationException("Enter a 10-digit mobile number.");
            }
            if (!phone.equals(user.getPhone())
                    && userRepository.existsByPhoneAndIdNot(phone, userId)) {
                throw DuplicateResourceException.phoneExists();
            }
            user.setPhone(phone);
        }

        user.setName(name);

        String role = roleOf(request.role());
        if (isAdmin(user) && !role.equals(user.getRole())) {
            throw ConflictException.adminAccountProtected(describe(user), "demoted to " + role);
        }
        user.setRole(role);

        String status = statusOf(request.status());
        if (isAdmin(user) && !status.equalsIgnoreCase(String.valueOf(user.getStatus()))) {
            throw ConflictException.adminAccountProtected(describe(user), "set to " + status);
        }
        user.setStatus(status);

        // Optional on purpose: the panel never sends one, and an admin provisioning a
        // usable login (or replacing a lost credential) is the case this covers. A blank
        // value leaves the stored hash untouched — never "set the password to blank".
        String password = passwordOrNull(request.password());
        if (password != null) {
            user.setPassword(password);
        }

        return AdminUserResponse.of(userRepository.save(user));
    }

    /**
     * Activates or deactivates an account — the admin panel's toggle.
     *
     * <p><b>Idempotent:</b> setting the status the account already has is a no-op, not an
     * error, so a double-click is harmless (the same rule the booking status endpoint
     * follows).
     *
     * <p><b>An {@code ADMIN} may be activated and never deactivated.</b> Written as "refuse
     * the transition to Inactive" rather than "refuse any change" deliberately: a legacy
     * row that is somehow inactive must stay repairable through this endpoint. Everything
     * else about the account is untouched — its bookings, passengers, payments and tickets
     * all stay exactly as they are, because deactivating is a sign-in decision, not a
     * deletion.
     *
     * @param status {@code Active} or {@code Inactive}, any case
     * @throws ResourceNotFoundException 404 {@code USER_NOT_FOUND}
     * @throws ConflictException         409 {@code ADMIN_ACCOUNT_PROTECTED}
     */
    @Transactional
    public AdminUserResponse updateStatus(int userId, String status) {
        User user = require(userId);
        String target = statusOf(status);

        if (isAdmin(user) && STATUS_INACTIVE.equals(target)) {
            throw ConflictException.adminAccountProtected(describe(user), "deactivated");
        }

        user.setStatus(target);
        return AdminUserResponse.of(userRepository.save(user));
    }

    /**
     * Hard-deletes an account — the panel's delete button.
     *
     * <p><b>Two refusals, both 409, both with a remedy in the message.</b>
     * <ul>
     *   <li>An {@code ADMIN} is never deleted: the rule the panel's own header states
     *       ("a disabled admin could lock everyone out of the panel").</li>
     *   <li>An account with bookings is not deleted either. {@code booking.user_id} is the
     *       only reference to a user in the schema, so the database would refuse this with
     *       driver error {@code 1451} — which the global handler can only report as a generic
     *       {@code RECORD_IN_USE}. The pre-check turns that into "N bookings on record — set
     *       the account to Inactive instead", which is also what keeps the admin page's own
     *       promise true: deactivating really does leave every booking row intact.</li>
     * </ul>
     *
     * <p>What deletion is <i>for</i>: the duplicate or mistaken roster records an admin
     * creates by hand, which own nothing yet. Anything with history is deactivated, not
     * erased — the same reasoning as R4 for bookings.
     *
     * @throws ResourceNotFoundException 404 {@code USER_NOT_FOUND}
     * @throws ConflictException         409 {@code ADMIN_ACCOUNT_PROTECTED} /
     *                                   {@code USER_HAS_BOOKINGS}
     */
    @Transactional
    public void deleteUser(int userId) {
        User user = require(userId);

        if (isAdmin(user)) {
            throw ConflictException.adminAccountProtected(describe(user), "deleted");
        }

        long owned = bookings.countByUserId(userId);
        if (owned > 0) {
            throw ConflictException.userHasBookings(describe(user), owned);
        }

        userRepository.delete(user);
    }

    /* ------------------------------------------------------------------ *
     *  Spring Security integration                                        *
     * ------------------------------------------------------------------ */

    /**
     * Loads a principal for Spring Security. The role goes through
     * {@link Authorities#of(String)} and nowhere else, so {@code hasRole(...)}
     * matches; {@code status} maps to {@code disabled} so an Inactive account
     * cannot authenticate through the framework path.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        User user = findByIdentifier(loginId).orElseThrow(() -> new UsernameNotFoundException(
                "No account matches the supplied identifier."));

        String username = user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail()
                : String.valueOf(user.getPhone());

        return org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password(String.valueOf(user.getPassword() == null ? "" : user.getPassword()))
                .authorities(Authorities.of(user.getRole()))
                .disabled(!"Active".equalsIgnoreCase(String.valueOf(user.getStatus())))
                .build();
    }

    /* ------------------------------------------------------------------ *
     *  Helpers                                                            *
     * ------------------------------------------------------------------ */

    /**
     * Resolves the single sign-in box to a row: anything containing {@code @} is
     * treated as an email (case-insensitive, matching {@code findByEmailIgnoreCase}
     * and the mock's {@code findUser}), everything else as a mobile number in its
     * stored 10-digit form.
     */
    private Optional<User> findByIdentifier(String loginId) {
        String value = String.valueOf(loginId == null ? "" : loginId).trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.contains("@")) {
            return userRepository.findByEmailIgnoreCase(value);
        }
        String digits = normalizePhone(value);
        return digits.isEmpty() ? Optional.empty() : userRepository.findByPhone(digits);
    }

    /** {@code null} and {@code ""} both mean "not supplied" — see the class docs. */
    private static String blankToNull(String value) {
        String trimmed = String.valueOf(value == null ? "" : value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Mirrors {@code MockDB.normalizePhone}: digits only, and a {@code +977}
     * country code is stripped, so {@code "+977 9812345678"} and
     * {@code "9812345678"} are one account rather than two.
     */
    private static String normalizePhone(String value) {
        String digits = String.valueOf(value == null ? "" : value).replaceAll("\\D", "");
        return digits.length() == 13 && digits.startsWith("977") ? digits.substring(3) : digits;
    }

    /** The full name exactly as the mock builds it: first / middle / last, blanks dropped. */
    private static String fullName(RegisterRequest request) {
        List<String> parts = Arrays.asList(request.firstName(), request.middleName(),
                        request.lastName()).stream()
                .map(UserService::blankToNull)
                .filter(part -> part != null)
                .toList();
        return String.join(" ", parts);
    }

    /* ------------------------------------------------------------------ *
     *  Admin helpers                                                      *
     * ------------------------------------------------------------------ */

    /**
     * The one lookup every admin write starts with, so no method invents its own 404 —
     * same shape as {@code BookingService.require(int)}.
     *
     * @throws ResourceNotFoundException 404 {@code USER_NOT_FOUND}
     */
    private User require(int userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
    }

    /**
     * Whether this is a panel account. Case-insensitive and null-safe because the column
     * is a plain {@code varchar} that older rows may hold in either case — the roster's
     * seeded rows and every write path use upper-case, but a check that only trusted the
     * spelling could let a mis-cased row be deactivated.
     */
    private static boolean isAdmin(User user) {
        return ROLE_ADMIN.equalsIgnoreCase(String.valueOf(user.getRole()).trim());
    }

    /**
     * What the refusal messages call an account: its name, or its id when the name is
     * missing. The column is nullable, so "ADMIN account null cannot be deactivated" is a
     * message that can genuinely be produced — and one that tells the admin nothing.
     */
    private static String describe(User user) {
        String name = blankToNull(user.getName());
        return name == null ? "account #" + user.getId() : name;
    }

    /** Whether the account may sign in — the state {@code authenticate} now enforces. */
    private static boolean isActive(User user) {
        return STATUS_ACTIVE.equalsIgnoreCase(String.valueOf(user.getStatus()).trim());
    }

    /**
     * The stored form of a requested role, or the panel's default for a new account.
     *
     * <p>The {@code @Pattern} on {@link AdminUserRequest} already refuses an unknown
     * value with a 400 before this runs; the check is repeated here because the rule
     * belongs to the service's own invariants, not to one annotation on one DTO — the
     * same reasoning as the duplicated identifier rule in {@code createUser}.
     */
    private static String roleOf(String requested) {
        String value = String.valueOf(requested == null ? "" : requested).trim().toUpperCase(Locale.ROOT);

        if (value.isEmpty()) {
            return ROLE_USER;
        }
        if (ROLE_ADMIN.equals(value) || ROLE_USER.equals(value)) {
            return value;
        }
        throw new ValidationException("Role must be ADMIN or USER.");
    }

    /** The stored spelling of a requested status ({@code Active} / {@code Inactive}). */
    private static String statusOf(String requested) {
        String value = String.valueOf(requested == null ? "" : requested).trim();

        if (value.isEmpty()) {
            return STATUS_ACTIVE;
        }
        if (STATUS_ACTIVE.equalsIgnoreCase(value)) {
            return STATUS_ACTIVE;
        }
        if (STATUS_INACTIVE.equalsIgnoreCase(value)) {
            return STATUS_INACTIVE;
        }
        throw new ValidationException("Status must be Active or Inactive.");
    }

    /**
     * A BCrypt hash for an optional password, or {@code null} for "no password".
     *
     * <p>Blank and absent both mean the same thing and both answer {@code null} — the
     * column is nullable, and a blank hash would be a credential that can never match
     * anything while still looking like one. The value is <b>not</b> trimmed: a password is
     * bytes the user chose, and quietly stripping the first character of one would be a
     * login bug nobody could see.
     */
    private String passwordOrNull(String raw) {
        String value = String.valueOf(raw == null ? "" : raw);
        return value.isBlank() ? null : passwordEncoder.encode(value);
    }

    /** A {@code LIKE} pattern for the free-text search, or {@code null} for "no filter". */
    private static String like(String search) {
        String term = String.valueOf(search == null ? "" : search).trim().toLowerCase(Locale.ROOT);
        return term.isEmpty() ? null : "%" + term + "%";
    }

    /**
     * The same search term as an id, when it is one — otherwise {@code null}.
     *
     * <p>The page's search box does {@code String(u.id).includes(q)}, so pasting an id it
     * just handed back has to work. Kept out of the JPQL for the reason
     * {@code BookingService.idTerm} documents: {@code cast(u.id as string)} inside a
     * {@code LIKE} is a dialect-dependent expression, and the id is a number here that
     * would otherwise be matched against names. The length guard keeps a long paste from
     * overflowing an {@code int}.
     */
    private static Integer idTerm(String search) {
        String term = String.valueOf(search == null ? "" : search).trim();
        if (term.isEmpty() || term.length() > 9) {
            return null;
        }
        return term.chars().allMatch(Character::isDigit) ? Integer.valueOf(term) : null;
    }

    /**
     * The stored form of a requested role filter, or {@code null} for "no filter".
     *
     * <p>{@code ALL} is folded to {@code null} as well as blank: the page's role dropdown
     * sends {@code ALL} for "no filter", and passing it straight through would return zero
     * rows — the exact opposite of what the caller asked for. The query compares
     * {@code upper(u.role)}, so the value is upper-cased here.
     */
    private static String storedRole(String role) {
        String value = String.valueOf(role == null ? "" : role).trim().toUpperCase(Locale.ROOT);
        return value.isEmpty() || "ALL".equals(value) ? null : value;
    }

    /**
     * The stored form of a requested status filter, lower-cased because the query compares
     * {@code lower(u.status)} against the title-case column — {@code status=active} and
     * {@code status=Active} must filter the same rows, and the page's select sends
     * {@code Active}/{@code Inactive}/{@code ALL}.
     */
    private static String storedStatus(String status) {
        String value = String.valueOf(status == null ? "" : status).trim();
        if (value.isEmpty() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a requested sort to a whitelisted property, always finishing with
     * {@code id} so accounts sharing a status or a role still page in a stable order.
     * Anything unrecognised falls back to {@code id} — the value never reaches the
     * database (R6).
     */
    private static Sort sortFor(String requested) {
        String key = String.valueOf(requested == null ? "" : requested).trim().toLowerCase(Locale.ROOT);
        String property = SORTABLE.getOrDefault(key, DEFAULT_SORT);

        return property.equals(DEFAULT_SORT)
                ? Sort.by(Sort.Order.asc(property))
                : Sort.by(Sort.Order.asc(property), Sort.Order.asc(DEFAULT_SORT));
    }
}
