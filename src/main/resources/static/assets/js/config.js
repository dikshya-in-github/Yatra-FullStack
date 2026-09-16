/* =====================================================
   YATRA 2.0 — CONFIG (spec §35)
   The single switch between the mock layer and the
   real Spring API (spec §2 / §41).

   Phase 1 (now):   USE_MOCK_DATA = true
   Phase 3 (later): USE_MOCK_DATA = false  + set API_BASE_URL
                    (e.g. "http://localhost:8080")

   This is the ONLY file that changes when the backend
   lands. Pages never branch on mock/real themselves —
   they only call apiGet()/apiPost() (spec Rule 7).

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
    ADMIN_SESSION_KEY: "yatra_admin_session"
};

/* Derived globals — do not edit these two lines; edit YATRA_CONFIG above. */
var USE_MOCK_DATA = YATRA_CONFIG.USE_MOCK_DATA;
var API_BASE_URL = YATRA_CONFIG.API_BASE_URL;
