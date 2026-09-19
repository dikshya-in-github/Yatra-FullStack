/* =====================================================
   YATRA 2.0 — CONFIG (spec §35)
   The single switch between the mock layer and the
   real Spring API (spec §2 / §41).

   HOW THE SWITCH WORKS NOW (fix-plan §1 + §13; execution step 4, 2026-09-18):
     USE_MOCK_DATA is still the one switch, but it is evaluated
     PER PAGE against REAL_API_PAGES below. A page named there
     talks to the real Spring API; every page not named there
     keeps using mock-data.js. That is what allows the migration
     to happen one page at a time instead of all at once.

   This is the ONLY file that changes as pages are wired up.
   Pages never branch on mock/real themselves — they only call
   apiGet()/apiPost()/apiPut()/apiDelete() (spec Rule 7).

   The bare USE_MOCK_DATA / API_BASE_URL globals are what
   api.js reads (matching the spec's §35 sketch); they are
   derived from YATRA_CONFIG so there is still just ONE
   place to edit. YATRA_CONFIG also collects future
   settings (e.g. the JWT storage key for §3.3).
   ===================================================== */

var YATRA_CONFIG = {
    /** true  → api.js serves data from mock-data.js (browser stores).
        false → api.js calls the real REST API at API_BASE_URL. */
    USE_MOCK_DATA: true,

    /** Root URL of the Spring API used when USE_MOCK_DATA = false.
        No trailing slash — endpoint paths start with /api/... */
    API_BASE_URL: "http://localhost:8080",

    /** Auth session keys (§3.3) — written by auth.js, read by api.js
        (to attach Authorization: Bearer <token>) and by pages that
        show the signed-in user. sessionStorage: matches §34 — the
        client session is per-tab, like the wizard handoff. */
    AUTH_TOKEN_KEY: "yatra_auth_token",
    AUTH_USER_KEY: "yatra_auth_user",

    /** Admin-panel session key (Master Plan §2.3). Deliberately SEPARATE from
        the customer session: the admin panel has its own sign-in page and the
        mock guard mirrors the future role check, so an admin session and a
        customer session can coexist in one tab without overwriting each other.
        Written by admin-login.html, read by admin.js (the guard) and by
        api.js's GET/POST /api/admin/profile. */
    ADMIN_SESSION_KEY: "yatra_admin_session",

    /* ---------------------------------------------------------------------
       Fix-plan §1 + §13 (execution step 4) — the REAL-API ALLOW-LIST.

       `USE_MOCK_DATA` used to be one switch for every page at once, which is
       why integration never started: the moment it was flipped, EVERY page had
       to be ready — including the admin modules whose writes still go to their
       own localStorage stores (see api.js's PUT/DELETE note), where a save
       would have toasted success and then vanished on the next read.

       This list inverts that. Name a page here only when BOTH its reads AND
       its writes are migrated; pages not named here keep using mock-data.js.
       When the last page moves, this list and mock-data.js both go.

       Matching is on the FILE NAME only (`login.html`), never a full path, so
       a context path or trailing slash cannot change the answer. `"/"` counts
       as `home.html`: PageController redirects it there, and a data source
       should not depend on winning that race. */
    REAL_API_PAGES: [
        /* §1 — the real auth the plan asks for: POST /api/auth/login and
           /api/auth/register, reached through YatraAuth (execution step 4). */
        "login.html",
        "signup.html",
        "admin-login.html",  /* §1 + §13 — the panel's sign-in and its real role check */

        /* §12 + §13 — the Flights module, the reference pattern for §12's five-step
           workflow (execution step 5): GET /api/admin/flights + /api/airlines +
           /api/destinations for reads, POST/PUT/DELETE /api/admin/flights for
           writes. It is named here only because its WRITES moved with its reads; a
           page whose reads are real but whose writes still land in localStorage
           must stay off this list. */
        "admin-flights.html",

        /* §12 + §13 — the Airlines module (Session 64), migrated to the same
           pattern as Flights directly above: the list is a server QUERY
           (GET /api/airlines?search=&status=&page=&size=, so the counts and the
           pages are MySQL's), the edit modal reads its row fresh from
           GET /api/airlines/{id}, and the writes are POST/PUT/DELETE
           /api/admin/airlines. It is named here only because its WRITES moved
           with its reads: the page's own store (`yatra_admin_airlines`,
           SEED_AIRLINES, save()) is gone, which is what made §12's step 2 look
           like it worked while nothing reached the database. */
        "admin-airlines.html",

        /* §12 + §13 — the Destinations module, migrated the same way: the list is
           a server QUERY (GET /api/admin/destinations?search=&status=&page=&size=),
           the edit modal reads its row fresh from GET /api/destinations/{id}, and
           the writes are POST/PUT/DELETE /api/admin/destinations. It is the first
           page that also calls an endpoint the others do not —
           POST /api/admin/destinations/image, the multipart Cloudinary upload that
           replaces the demo's data URL — which api.js reaches through apiUpload().
           Named here only because its WRITES moved with its reads. */
        "admin-destinations.html",

        /* §7 + §11 — the signed-in account. profile.html is §11's page; both pages
           call ONLY GET/POST/PUT /api/users/me and GET /api/users/me/bookings, so
           naming them here moves no unrelated call with them. They are named
           together because profile.js's "Total bookings" and my-bookings.html's own
           total read that one endpoint, and the pages' comments say the two counts
           "can never disagree" — switching one without the other would make them.

           booking.html is deliberately NOT named, even though §7 is about it. Its
           pre-fill reaches GET /api/users/me through the same api.js call and gets
           the real account either way — see booking.js §12. Naming it here would
           also move POST /api/bookings to the real backend while payment.html is
           still mock (§10), creating PENDING bookings holding real seats that
           nothing sweeps (the hold sweep is off by default). It joins this list
           with payment.html, in §10. */
        "profile.html",
        "my-bookings.html"
    ]
};

/** The page name the allow-list matches on: file name only, lower-cased. */
function yatraCurrentPageName() {
    var path = String((typeof window !== "undefined" && window.location &&
        window.location.pathname) || "");
    var name = path.substring(path.lastIndexOf("/") + 1);
    return (name || "home.html").toLowerCase();
}

/** true when THIS page is on the allow-list — the per-page half of §4. */
function yatraPageUsesRealApi() {
    var pages = YATRA_CONFIG.REAL_API_PAGES || [];
    var current = yatraCurrentPageName();
    for (var i = 0; i < pages.length; i++) {
        if (String(pages[i]).toLowerCase() === current) return true;
    }
    return false;
}

/* Derived globals — do not edit these two lines; edit YATRA_CONFIG above.
   `USE_MOCK_DATA` keeps its name and its meaning for every caller (api.js reads
   it in four places; a page may read it to label its own data). What changed is
   WHEN it is decided: per page, off the allow-list, instead of once for the
   whole site. */
var USE_MOCK_DATA = YATRA_CONFIG.USE_MOCK_DATA && !yatraPageUsesRealApi();
var API_BASE_URL = YATRA_CONFIG.API_BASE_URL;
