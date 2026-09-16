/* =====================================================
   YATRA 2.0 — AUTH (spec §35 "auth.js")

   register() / login() / logout() — the only auth entry points
   the pages call. Both network calls go through api.js (spec
   Rule 7: pages never call fetch), so the SAME page code works
   in mock mode today and against Spring Security in Phase 3:

     POST /api/auth/register   → creates the account, returns { token, user }
     POST /api/auth/login      → validates credentials,  returns { token, user }

   SESSION SHAPE (§3.3, Master Plan — JWT-ready):
     sessionStorage[YATRA_CONFIG.AUTH_TOKEN_KEY] → the token
        In mock mode: an unsigned JWT-shaped string minted by api.js.
        In Phase 3: the real signed JWT from JwtService.
        api.js's real-mode branch attaches it as
        "Authorization: Bearer <token>" on every request.
     sessionStorage[YATRA_CONFIG.AUTH_USER_KEY]  → the public user
        { userId, name, email, phone, role, status } for page chrome
        (navbar chip, my-bookings scoping). Never a password or hash.

   Key names live in config.js so Phase 3 edits one file, not the pages.
   Passwords are never stored or compared client-side: the mock drops
   the password field, and BCryptPasswordEncoder owns it server-side
   (Master Plan §3.4). In mock mode an unknown identifier still returns
   a session so a fresh browser can walk the demo — the same
   never-dead-end rule the mock eSewa gateway follows.

   Also owns the navbar session chrome (renderSessionChrome): the chip's
   name/initial and the "Log Out" links. It lives here because this file
   owns the token and the cached user — no page has to know the key, and
   the pages that are not session-aware simply never load this file.
   ===================================================== */

var YatraAuth = (function () {
    "use strict";

    var cfg = (typeof YATRA_CONFIG !== "undefined") ? YATRA_CONFIG : {};
    var TOKEN_KEY = cfg.AUTH_TOKEN_KEY || "yatra_auth_token";
    var USER_KEY = cfg.AUTH_USER_KEY || "yatra_auth_user";

    /** Marks a logout link that already has its click listener, so binding
        twice (shared navbar JS + a page's own script) never stacks them. */
    var BOUND_ATTR = "data-logout-bound";

    /* ---------- Session ---------- */

    function saveSession(res) {
        res = res || {};
        if (res.token) sessionStorage.setItem(TOKEN_KEY, res.token);
        sessionStorage.setItem(USER_KEY, JSON.stringify(res.user || null));
        return res.user || null;
    }

    function token() {
        return sessionStorage.getItem(TOKEN_KEY);
    }

    function currentUser() {
        try {
            return JSON.parse(sessionStorage.getItem(USER_KEY));
        } catch (err) {
            return null; // corrupted/absent session
        }
    }

    function isLoggedIn() {
        return !!token();
    }

    /**
     * Replace the cached public user WITHOUT touching the token — what a
     * profile edit needs (POST /api/users/me returns the updated UserResponse,
     * and the navbar chip / my-bookings scope must agree with it straight
     * away). Session writes stay in this file so no page has to know the key.
     */
    function updateCurrentUser(user) {
        sessionStorage.setItem(USER_KEY, JSON.stringify(user || null));
        return user || null;
    }

    /* ---------- Public API ---------- */

    /**
     * Create an account. `payload` is the signup form data
     * ({ title, firstName, middleName, lastName, phone, email,
     * password, method }) — the password is sent to the API and
     * never persisted client-side.
     * Resolves with the new user; rejects with an ApiError
     * (e.g. 409 EMAIL_EXISTS).
     */
    function register(payload) {
        return apiPost("/api/auth/register", payload).then(saveSession);
    }

    /**
     * Start a session. Resolves with the user, rejects with an ApiError
     * (401 INVALID_CREDENTIALS once the backend verifies passwords).
     */
    function login(loginId, password) {
        return apiPost("/api/auth/login", {
            loginId: String(loginId == null ? "" : loginId).trim(),
            password: password
        }).then(saveSession);
    }

    /** End the session. Pages decide where to navigate afterwards. */
    function logout() {
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(USER_KEY);
        return null;
    }

    /**
     * Drop the session when a navbar "Log Out" link is clicked. The links
     * already navigate to home.html themselves; this only clears the
     * token/user first — without it the session outlives the logout.
     *
     * Idempotent: each link is bound once (marked with a data attribute),
     * so a page that calls this twice — or a page whose own script binds
     * them too — never stacks listeners.
     */
    function bindLogoutLinks(root) {
        var scope = root || document;
        var links = logoutLinkQuery(scope);
        Array.prototype.forEach.call(links, function (link) {
            if (link.getAttribute(BOUND_ATTR)) return;
            link.setAttribute(BOUND_ATTR, "1");
            link.addEventListener("click", function () { logout(); });
        });
        return links.length;
    }

    /* ---------- Navbar session chrome ---------- */

    /**
     * Every "Log Out" affordance in the shared navbar + mobile menu.
     *
     * The mobile-menu row carries no class or id, so it is found by its
     * sign-out icon — NOT by its href, which this module rewrites on every
     * render (looking it up by href lost the link after the first pass).
     */
    function logoutLinkQuery(scope) {
        var links = Array.prototype.slice.call(
            scope.querySelectorAll(".btn-logout, [data-logout]")
        );
        var menuLinks = scope.querySelectorAll(".mobile-menu a");
        Array.prototype.forEach.call(menuLinks, function (link) {
            if (links.indexOf(link) !== -1) return;
            if (link.querySelector(".fa-right-from-bracket") ||
                link.getAttribute("href") === "./home.html") {
                links.push(link);
            }
        });
        return links;
    }

    function initialOf(name) {
        var s = String(name == null ? "" : name).trim();
        return s ? s.charAt(0).toUpperCase() : "";
    }

    /**
     * Replace the label of an element that may start with an <i> icon,
     * keeping the icon (the mobile menu links are "<i> </i> Log Out").
     */
    function setLabel(el, text) {
        var icon = el.querySelector("i");
        el.textContent = "";
        if (icon) {
            el.appendChild(icon);
            el.appendChild(document.createTextNode(" " + text));
        } else {
            el.appendChild(document.createTextNode(text));
        }
    }

    /**
     * Fill the navbar user chip from the cached session and make "Log Out"
     * actually end the session (both the navbar button and the mobile-menu
     * link — they used to be plain links to home.html).
     *
     * Called by homeLogged.js on the pages that load the shared navbar JS,
     * and by the 5 wizard pages, which have their own navbar code. A page
     * that loads no auth.js never calls it, so those keep the static markup.
     *
     * Signed in  → the real name + initial, and the logout links still log out.
     * No session → a neutral "Guest" chip and the logout links become
     *              "Log In" links, so the navbar never shows a name that is
     *              not the visitor's (the hardcoded "Dikshya Ghising" was
     *              showing to signed-out visitors too).
     */
    function renderSessionChrome(root) {
        var scope = root || document;
        var user = isLoggedIn() ? currentUser() : null;
        var name = user ? (user.name || user.email || "") : "";
        var label = name || "Guest";

        var chip = scope.querySelector(".user-chip");
        var avatar = scope.querySelector("#userAvatar") ||
            (chip && chip.querySelector(".user-avatar"));
        var chipName = scope.querySelector("#userName") ||
            (chip && chip.querySelector(".user-name"));

        if (chipName) chipName.textContent = label;
        if (avatar) avatar.textContent = initialOf(name) || "G";

        var mobileName = scope.querySelector(".mobile-user-name");
        if (mobileName) setLabel(mobileName, label);

        var links = logoutLinkQuery(scope);
        Array.prototype.forEach.call(links, function (link) {
            // Both states rewrite the link, so the chrome is correct whoever
            // renders last (a page that signs in without navigating away
            // must get its "Log Out" back).
            link.setAttribute("href", user ? "./home.html" : "./login.html");
            setLabel(link, user ? "Log Out" : "Log In");
            if (link.getAttribute(BOUND_ATTR)) return;
            link.setAttribute(BOUND_ATTR, "1");
            link.addEventListener("click", function () { logout(); });
        });

        return { name: label, links: links.length, signedIn: !!user };
    }

    return {
        TOKEN_KEY: TOKEN_KEY,
        USER_KEY: USER_KEY,
        register: register,
        login: login,
        logout: logout,
        token: token,
        currentUser: currentUser,
        updateCurrentUser: updateCurrentUser,
        isLoggedIn: isLoggedIn,
        bindLogoutLinks: bindLogoutLinks,
        renderSessionChrome: renderSessionChrome
    };
})();

/* =====================================================
   §35 spec names — register() / login() / logout() are listed by
   name in the spec, so they exist as globals too. Pages call the
   namespaced YatraAuth.* versions so it is obvious where the
   behaviour (and the session keys) come from.
   ===================================================== */

function register(payload) {
    return YatraAuth.register(payload);
}

function login(loginId, password) {
    return YatraAuth.login(loginId, password);
}

function logout() {
    return YatraAuth.logout();
}
