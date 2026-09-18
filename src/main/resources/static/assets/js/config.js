/* =====================================================
   YATRA 2.0 — CONFIG (spec §35)
   The single switch between the mock layer and the
   real Spring API (spec §2 / §41).

   HOW THE SWITCH WORKS NOW (fix plan §4, 2026-09-18):
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
       Fix plan §4 — the REAL-API ALLOW-LIST.

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
        "login.html",        /* §4 — YatraAuth.login → POST /api/auth/login */
        "signup.html",       /* §4 — YatraAuth.register → POST /api/auth/register */
        "admin-login.html",  /* §4/§13 — the panel's sign-in + a real role check */

        /* §5 — the Flights module, the reference pattern for §12's five-step
           workflow: GET /api/admin/flights + /api/airlines + /api/destinations
           for reads, POST/PUT/DELETE /api/admin/flights for writes. It is named
           here only because its WRITES moved with its reads; a page whose reads
           are real but whose writes still land in localStorage must stay off
           this list. */
        "admin-flights.html"
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
