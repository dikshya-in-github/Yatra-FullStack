/* =====================================================
   YATRA 2.0 — API WRAPPER (spec §35 "api.js")
   The ONLY place in the frontend that knows whether data
   comes from the mock layer or the real Spring API.

     USE_MOCK_DATA = true   (Phase 1 — now)
       → routes to the mock handlers in mock-data.js
     USE_MOCK_DATA = false  (Phase 3 — after the backend)
       → real fetch() calls against API_BASE_URL (CORS)

   Pages call apiGet()/apiPost() and never call fetch or
   branch on mock/real themselves (spec Rule 7) — so the
   SAME page code serves both phases.

   §36 API CONTRACT (mock paths mirror these exactly):
     GET  /api/flights/search?origin=&destination=&date=&passengers=
     POST /api/bookings
     POST /api/payments/initiate
     POST /api/payments/verify
     GET  /api/bookings/{id}/ticket        (planned)
     GET  /api/bookings/{id}
     GET  /api/users/me/bookings           (planned)
     GET  /api/users/me
     POST /api/users/me                    (own profile — self-service)
     GET  /api/destinations
     GET  /api/airlines
     GET  /api/admin/dashboard             (+ Phase 13 `stats`)
     GET  /api/admin/bookings              (planned)
     GET  /api/admin/payments               (planned)
     GET  /api/admin/tickets               (planned)
     GET  /api/admin/flights               (planned)
     GET  /api/admin/users                 (planned)
     GET  /api/admin/destinations          (planned)
     GET  /api/admin/profile               (the admin's own account)
     POST /api/admin/profile
     POST /api/auth/register
     POST /api/auth/login

   Errors reject with ApiError — same JSON shape the
   backend's GlobalExceptionHandler will return:
     { "error": "CODE", "message": "..." }
   ===================================================== */

/* config.js fallbacks (default = mock mode) in case it failed to load */
if (typeof USE_MOCK_DATA === "undefined") { window.USE_MOCK_DATA = true; }
if (typeof API_BASE_URL === "undefined") { window.API_BASE_URL = "http://localhost:8080"; }

/* ---------- Error type ---------- */

function ApiError(status, code, message) {
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.message = message;
}

/* ---------- Auth helpers (§35 auth.js / §3.3) ---------- */

function digitsOf(value) {
    return String(value == null ? "" : value).replace(/\D/g, "");
}

/* The public user shape: what the backend's UserResponse DTO returns and
   what auth.js stores for page chrome. Never the stored record verbatim —
   no password/hash ever crosses this boundary (spec's DTO rule, §3.4). */
function publicUser(user) {
    user = user || {};
    return {
        userId: user.id != null ? user.id : 0,
        name: user.name || "",
        email: user.email || "",
        phone: user.phone || "",
        role: user.role || "USER",
        status: user.status || "Active",
        registeredAt: user.registeredAt || ""
    };
}

function base64urlJson(obj) {
    var json = JSON.stringify(obj);
    var b64 = (typeof btoa === "function")
        ? btoa(unescape(encodeURIComponent(json)))
        : json; // non-browser (test harness) — shape still round-trips
    return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/* UNSIGNED JWT-shaped token (mock only). The payload carries exactly the
   claims the real JwtService will mint (sub, name, role, iat, exp), so
   auth.js and the real-mode branch can treat mock and real tokens the same
   way. The signature segment is a placeholder — the backend signs for real. */
function mockJwt(user) {
    var now = Math.floor(Date.now() / 1000);
    return base64urlJson({ alg: "HS256", typ: "JWT" }) + "." +
        base64urlJson({
            sub: String(user.id != null ? user.id : 0),
            name: user.name || "",
            email: user.email || "",
            role: user.role || "USER",
            iat: now,
            exp: now + 8 * 60 * 60 // 8-hour session (backend default)
        }) + ".mock-signature-not-verified";
}
ApiError.prototype = Object.create(Error.prototype);
ApiError.prototype.toString = function () {
    return this.name + " " + this.status + " [" + this.code + "]: " + this.message;
};

/* ---------- Mock session helpers (mock mode only) ----------
   /api/users/me and /api/admin/profile take NO identifier: in Phase 3 Spring
   Security resolves the principal from the JWT's `sub` claim, so the same page
   code sends the same empty body. The mock has no per-request context, so it
   reads the very session keys auth.js (customer) and admin.js (admin panel)
   wrote and matches that identity against the roster — the closest thing a
   browser store has to "the authenticated principal". */
function sessionJson(key) {
    try {
        var raw = sessionStorage.getItem(key);
        return raw ? JSON.parse(raw) : null;
    } catch (err) {
        return null; // absent or corrupted → treated as "not signed in"
    }
}

function mockSessionUser() {
    var cfg = (typeof YATRA_CONFIG !== "undefined") ? YATRA_CONFIG : {};
    return sessionJson(cfg.AUTH_USER_KEY || "yatra_auth_user");
}

function mockAdminSession() {
    var cfg = (typeof YATRA_CONFIG !== "undefined") ? YATRA_CONFIG : {};
    return sessionJson(cfg.ADMIN_SESSION_KEY || "yatra_admin_session");
}

/* Map a signed-in identity onto a roster row.
   Mock login can mint a session for an identifier that was never registered
   (the demo never-dead-end rule), so a miss is not an error here — the callers
   decide whether to synthesise a record or create one. */
function rosterUserFor(identity) {
    identity = identity || {};
    return MockDB.findUser(identity.email || identity.phone || identity.name || "");
}

/* The shared body of the two profile writes: uniqueness first (the real schema
   has both unique constraints, so the mock answers with the same 409 codes
   GlobalExceptionHandler will), then the store write. */
function updateProfile(patch, currentId, fallback) {
    var email = String(patch.email == null ? "" : patch.email).trim();
    var phone = digitsOf(patch.phone);

    if (email && MockDB.findUserExcluding(email, currentId)) {
        throw new ApiError(409, "EMAIL_EXISTS", "An account with this email already exists.");
    }
    if (phone && MockDB.findUserExcluding(phone, currentId)) {
        throw new ApiError(409, "PHONE_EXISTS", "An account with this mobile number already exists.");
    }

    var updated = (currentId ? MockDB.updateUser(currentId, {
        name: patch.name, email: email, phone: phone
    }) : null);

    if (updated) return updated;

    /* No roster row for this session (a demo login that was never registered).
       Persist it when we have something to key on, so the edit survives a
       reload and the account shows up in admin-users.html — the same rule
       POST /api/auth/register follows. With no email AND no mobile there is
       nothing unique to store, so we answer with the edited shape only. */
    if (email || phone) {
        updated = MockDB.addUser({
            name: patch.name, email: email, phone: phone,
            role: (fallback && fallback.role) || "USER",
            status: (fallback && fallback.status) || "Active"
        });
        if (updated) return updated;
    }

    return {
        id: (fallback && fallback.userId) || 0,
        name: patch.name || (fallback && fallback.name) || "",
        email: email || (fallback && fallback.email) || "",
        phone: phone || (fallback && fallback.phone) || "",
        role: (fallback && fallback.role) || "USER",
        status: (fallback && fallback.status) || "Active"
    };
}

/* =====================================================
   Mock mode — route table
   Handlers resolve as Promises so page code is identical
   for mock and real mode (both are async).
   ===================================================== */

/* Shared by the two flight writes below (POST + PUT).

   The mock is the API's specification, so a flight write has to refuse what
   FlightService.apply() refuses, with the same status and code — otherwise the
   mock accepts a body the real API rejects and the page's error path is never
   exercised. The rules, and where they come from:
     airline not found  → 404 NOT_FOUND          (ResourceNotFoundException.of)
     airport not found  → 400 UNKNOWN_AIRPORT    (FlightService.resolveAirport)
     origin = destination → 400 VALIDATION_FAILED (FlightService.apply)
     arrival <= departure → 400 VALIDATION_FAILED (FlightService.apply)
   `date` is carried through as sent — including absent-as-null, because
   apply() deliberately overwrites the stored date with whatever the request
   carried, and only the page knows whether this flight has one. */
function mockFlightBody(body) {
    body = body || {};

    var no = MockDB.normalizeFlightNo(body.no);
    var from = String(body.from == null ? "" : body.from).toUpperCase();
    var to = String(body.to == null ? "" : body.to).toUpperCase();

    if (body.airlineId == null || !MockDB.getAirlines().some(function (a) {
        return String(a.id) === String(body.airlineId);
    })) {
        throw new ApiError(404, "NOT_FOUND", "Airline " + body.airlineId + " not found");
    }
    if (!MockDB.getDestinations().some(function (d) { return d.code === from; })) {
        throw new ApiError(400, "UNKNOWN_AIRPORT", "No airport exists with code " + from + ".");
    }
    if (!MockDB.getDestinations().some(function (d) { return d.code === to; })) {
        throw new ApiError(400, "UNKNOWN_AIRPORT", "No airport exists with code " + to + ".");
    }
    if (from === to) {
        throw new ApiError(400, "VALIDATION_FAILED", "Origin and destination cannot be the same.");
    }
    if (String(body.arr) <= String(body.dep)) {
        throw new ApiError(400, "VALIDATION_FAILED", "Arrival must be after departure (same-day domestic flights).");
    }

    return {
        no: no,
        airlineId: Number(body.airlineId),
        from: from,
        to: to,
        dep: body.dep,
        arr: body.arr,
        aircraft: body.aircraft || "",
        fare: Number(body.fare),
        seats: Number(body.seats),
        status: body.status || "Active",
        date: body.date || null
    };
}

/* The flight number is unique across the store, canonicalised before the
   comparison — the mock's version of the uk_flight_no constraint, answered with
   the code DuplicateResourceException.flightNoExists uses. `excludeId` is the
   row being edited, which is allowed to keep its own number. */
function assertFlightNoFree(no, excludeId) {
    var taken = MockDB.getFlights().some(function (f) {
        return String(f.id) !== String(excludeId === undefined ? "" : excludeId)
            && MockDB.normalizeFlightNo(f.no) === no;
    });
    if (taken) {
        throw new ApiError(409, "FLIGHT_NO_EXISTS", "Flight number " + no + " already exists.");
    }
}

/* The dashboard's §2.5 cards (Phase 13) — the mock's mirror of the count/sum
   queries GET /api/admin/dashboard runs for real (Service/DashboardService).
   Same four definitions the page used to apply itself:
     revenue        sum of `amount` over NON-CANCELLED bookings
     todaysBookings bookings CREATED today
     pendingPayments bookings whose paymentStatus is 'Pending'
   The seed-size fallbacks (4/6/10) moved here with the arithmetic — they are
   still the preview the page shows while a store the owning page self-seeds is
   empty, and they belong on the fake side, never on the page. */
function mockDashboardPayload() {
    var airlines = MockDB.getAirlines();
    var flights = MockDB.getFlights();
    var bookings = MockDB.getBookings();
    var users = MockDB.getUsers();

    var live = bookings.filter(function (b) { return b.status !== "Cancelled"; });
    var revenue = live.reduce(function (sum, b) {
        return sum + (Number(b.amount) || 0);
    }, 0);
    var todayIso = new Date().toISOString().slice(0, 10);
    var todays = bookings.filter(function (b) {
        return String(b.createdAt || "").slice(0, 10) === todayIso;
    }).length;
    var pending = bookings.filter(function (b) { return b.paymentStatus === "Pending"; }).length;

    return {
        bookings: bookings,
        stats: {
            totalUsers: users.length || 10,      // 10 = seed roster size
            totalFlights: flights.length || 6,   // 6  = seed schedule size
            totalAirlines: airlines.length || 4, // 4  = seed fleet size
            totalBookings: bookings.length,
            todaysBookings: todays,
            revenue: revenue,
            pendingPayments: pending
        }
    };
}

var MOCK_GET_ROUTES = [
    { pattern: /^\/api\/flights\/search$/, handle: function (match, params) {
        return MockDB.searchFlights({
            origin: params.get("origin"),
            destination: params.get("destination"),
            date: params.get("date"),
            passengers: params.get("passengers")
        });
    } },

    /* Customer booking history — GET /api/users/me/bookings and the
       flat /api/bookings list both read the shared yatra_bookings
       store (no per-user scoping until JWT auth lands, §3.3). */
    { pattern: /^\/api\/(users\/me\/)?bookings$/, handle: function () {
        return { bookings: MockDB.getBookings() };
    } },
    { pattern: /^\/api\/bookings\/([^/]+)$/, handle: function (match) {
        var booking = MockDB.getBookingById(decodeURIComponent(match[1]));
        if (!booking) {
            throw new ApiError(404, "BOOKING_NOT_FOUND", "No booking with id " + match[1]);
        }
        return { booking: booking };
    } },

    /* The signed-in user's own account (profile.html). Same { user } shape the
       backend's UserResponse DTO returns; never a password/hash (§3.4). */
    { pattern: /^\/api\/users\/me$/, handle: function () {
        var session = mockSessionUser();
        var record = rosterUserFor(session);
        if (record) return { user: publicUser(record) };

        /* Unregistered demo session → synthesise the public shape from it so
           the profile page always has something to render (never dead-end). */
        return { user: publicUser({
            id: (session && session.userId) || 0,
            name: (session && session.name) || "Demo Traveller",
            email: (session && session.email) || "",
            phone: (session && session.phone) || "",
            role: (session && session.role) || "USER",
            status: (session && session.status) || "Active",
            registeredAt: (session && session.registeredAt) || ""
        }) };
    } },

    { pattern: /^\/api\/destinations$/, handle: function () {
        return { destinations: MockDB.getDestinations() };
    } },
    { pattern: /^\/api\/airlines$/, handle: function () {
        return { airlines: MockDB.getAirlines() };
    } },

    /* ---------- Admin reads (item 16) ----------
       Mock responses mirror the §36/§2.4 admin shapes so the Spring
       API can return the same JSON later (real routes protected by
       @PreAuthorize("hasRole('ADMIN')") — Backend Roadmap Phases 4–13). */
    /* GET /api/admin/dashboard (Phase 13) — the real endpoint answers the booking
       rows plus a `stats` block it computes with count/sum queries; this mirrors
       that shape exactly, so admin-dashboard.js never branches on mock vs real. */
    { pattern: /^\/api\/admin\/dashboard$/, handle: function () {
        return mockDashboardPayload();
    } },
    { pattern: /^\/api\/admin\/bookings$/, handle: function () {
        return { bookings: MockDB.getBookings() };
    } },
    { pattern: /^\/api\/admin\/payments$/, handle: function () {
        return { bookings: MockDB.getBookings() }; // txns derived per booking (§3.6) — same shape the page consumes
    } },
    { pattern: /^\/api\/admin\/tickets$/, handle: function () {
        return { bookings: MockDB.getBookings() }; // tickets derived per booking — same shape the page consumes
    } },
    /* The admin flight list, with the query parameters the real controller and
       repository honour (fix plan §12; execution step 5). admin-flights.js sends
       search/airlineId/status/page/size and renders whatever comes back, so the
       mock has to filter and page HERE — handing back the whole store would make
       a migrated page render every row while its counts said "8", and the two
       modes would disagree about what a search means. Search fields match
       FlightRepository.searchPage: flight number, origin code, destination code,
       airline name. Without `size` it answers the unpaged shape (all matches). */
    { pattern: /^\/api\/admin\/flights$/, handle: function (match, params) {
        var term = String(params.get("search") || "").trim().toLowerCase();
        var airlineId = params.get("airlineId");
        var status = params.get("status");
        var roster = MockDB.getAirlines();

        var rows = MockDB.getFlights().filter(function (f) {
            var airline = roster.filter(function (a) {
                return String(a.id) === String(f.airlineId);
            })[0];
            var matchesTerm = !term
                || String(f.no).toLowerCase().indexOf(term) !== -1
                || String(f.from).toLowerCase().indexOf(term) !== -1
                || String(f.to).toLowerCase().indexOf(term) !== -1
                || (airline && String(airline.name).toLowerCase().indexOf(term) !== -1);

            return matchesTerm
                && (!airlineId || String(f.airlineId) === String(airlineId))
                && (!status || f.status === status);
        });

        var size = parseInt(params.get("size"), 10);
        if (!(size > 0)) return { flights: rows };

        var page = parseInt(params.get("page"), 10) || 0;
        return {
            flights: rows.slice(page * size, page * size + size),
            page: page,
            size: size,
            totalElements: rows.length,
            totalPages: Math.max(1, Math.ceil(rows.length / size))
        };
    } },
    { pattern: /^\/api\/admin\/users$/, handle: function () {
        return { users: MockDB.getUsers() };
    } },
    { pattern: /^\/api\/admin\/destinations$/, handle: function () {
        return { destinations: MockDB.getDestinations() };
    } },

    /* The admin's OWN account (admin-profile.html). In Phase 3 this route sits
       behind @PreAuthorize("hasRole('ADMIN')"), so the mock answers 401 when no
       admin session exists — the same rejection the page's guard would get. */
    { pattern: /^\/api\/admin\/profile$/, handle: function () {
        var admin = mockAdminSession();
        if (!admin || admin.role !== "ADMIN") {
            throw new ApiError(401, "NOT_AUTHENTICATED", "Admin sign-in required.");
        }

        var record = rosterUserFor(admin) || MockDB.findUser("admin@gmail.com");
        if (record) return { user: publicUser(record) };

        return { user: publicUser({
            id: admin.userId || 0,
            name: admin.name || "Admin",
            email: admin.email || "",
            phone: admin.phone || "",
            role: "ADMIN",
            status: "Active"
        }) };
    } }
];

var MOCK_POST_ROUTES = [
    /* §31.2 — creates the pending booking id that rides inside the
       existing `bookingData` sessionStorage key to the payment step
       (no new one-off keys). The REAL backend persists a PENDING row
       here; the mock returns the id only and writes the confirmed row
       in POST /api/payments/verify (below) — documented simplification
       so abandoned carts never pollute the shared yatra_bookings store. */
    { pattern: /^\/api\/bookings$/, handle: function (match, params, body) {
        body = body || {};
        return {
            bookingId: "BKG" + Date.now().toString().slice(-8),
            status: "PENDING",
            contact: body.contact || null,
            passengers: body.passengers || [],
            flight: body.flight || null,
            amount: body.amount != null ? body.amount : null
        };
    } },

    { pattern: /^\/api\/payments\/initiate$/, handle: function (match, params, body) {
        body = body || {};
        var method = String(body.method || "ESEWA").toUpperCase();
        return {
            paymentRef: "PAY" + Date.now().toString().slice(-8),
            bookingId: body.bookingId || null,
            method: method,
            amount: body.amount != null ? body.amount : null,
            gatewayRedirect: method === "ESEWA" ? "esewaLogin.html" : null
        };
    } },

    /* Mock gateway always succeeds (mirrors the esewaConfirm demo path)
       AND completes the booking the way the backend's PaymentService
       does inside the verify transaction: writes the confirmed row to
       yatra_bookings (deterministic PNR/ticket derivation) and bumps
       bookedSeats on the matching yatra_admin_flights row.
       The mock has no database, so the PENDING payload the real backend
       would look up by bookingId is re-sent by the page as body.booking
       (mock-only pragmatism — same request the real API accepts minus
       that field). bookingId, txnId and method match the real contract. */
    { pattern: /^\/api\/payments\/verify$/, handle: function (match, params, body) {
        body = body || {};
        var txnId = body.txnId || "";
        var bookingId = body.bookingId || null;
        var bk = body.booking || null;
        var persisted = false;

        if (bk && bk.flight) {
            var f = bk.flight;
            var ids = MockDB.derivePnrTicket(txnId);
            var contact = bk.contact || {};
            var paidAt = new Date().toISOString();
            MockDB.addBooking({
                id: bookingId || "BKG" + Date.now().toString().slice(-8),
                pnr: ids.pnr,
                ticketNo: ids.ticketNo,
                status: "Confirmed",
                paymentStatus: "Paid",
                customer: contact.firstName
                    ? (contact.firstName + " " + (contact.lastName || "")).trim()
                    : (body.customerName || ""),
                email: contact.email || "",
                phone: contact.phone || "",
                passengers: bk.passengers || [],
                flight: {
                    flightNo: f.flightNo || "",
                    airline: f.airline || null,
                    from: f.from || "",
                    to: f.to || "",
                    date: f.date || "",
                    depart: f.depart || "",
                    arrive: f.arrive || "",
                    flightClass: f.flightClass || "E Class",
                    refundable: !!f.refundable,
                    pricePerPassenger: f.pricePerPassenger != null ? f.pricePerPassenger : null,
                    passengerCount: f.passengerCount || 1
                },
                amount: bk.amount != null ? bk.amount : (body.amount != null ? body.amount : null),
                productAmount: body.productAmount != null ? body.productAmount : bk.amount,
                payment: { method: body.method || "eSewa", txnId: txnId, paidAt: paidAt },
                createdAt: paidAt
            });
            MockDB.incrementBookedSeats(f.flightNo || "", f.from || "", f.to || "", f.passengerCount || 1);
            persisted = true;
        }

        return {
            status: "SUCCESS",
            txnId: txnId,
            bookingId: bookingId,
            persisted: persisted,
            verifiedAt: new Date().toISOString()
        };
    } },

    /* Self-service profile writes. Only the fields a profile form owns are
       writable (name/email/phone) — role and status are NOT, which is why the
       body is passed straight to updateUser() rather than spread over the
       record. Returns the updated { user } so the page can re-render from the
       server's answer instead of trusting its own form state. */
    { pattern: /^\/api\/users\/me$/, handle: function (match, params, body) {
        body = body || {};
        var session = mockSessionUser();
        var record = rosterUserFor(session);
        var currentId = record ? record.id : ((session && session.userId) || 0);
        return { user: publicUser(updateProfile(
            body, currentId, session ? publicUser(session) : null)) };
    } },

    /* ---------- Auth (item 16 — spec §35 auth.js, §36, Master Plan §3.3) ----------
       Response shape is the real one — { token, user } — so login.html /
       signup.html do not change when Spring Security + JwtService land. */
    { pattern: /^\/api\/auth\/register$/, handle: function (match, params, body) {
        body = body || {};
        var email = String(body.email || "").trim();
        var phone = digitsOf(body.phone);

        /* Real uniqueness constraints (the MySQL schema has them): the
           mock answers with the same 409 codes the backend's
           GlobalExceptionHandler will return. */
        if (email && MockDB.findUser(email)) {
            throw new ApiError(409, "EMAIL_EXISTS", "An account with this email already exists.");
        }
        if (phone && MockDB.findUser(phone)) {
            throw new ApiError(409, "PHONE_EXISTS", "An account with this mobile number already exists.");
        }

        /* body.password is deliberately NOT stored: hashing belongs to the
           backend's BCryptPasswordEncoder (Master Plan §3.4). The mock keeps
           the same record shape the admin panel's seeded roster uses, which
           is why a fresh signup shows up in admin-users.html immediately. */
        var user = MockDB.addUser({
            name: [body.firstName, body.middleName, body.lastName]
                .filter(function (part) { return !!part; }).join(" ").trim(),
            email: email,
            phone: phone,
            role: "USER"
        });

        return { token: mockJwt(user), user: publicUser(user) };
    } },

    /* The admin's own account write (same 401 gate as the GET above). */
    { pattern: /^\/api\/admin\/profile$/, handle: function (match, params, body) {
        body = body || {};
        var admin = mockAdminSession();
        if (!admin || admin.role !== "ADMIN") {
            throw new ApiError(401, "NOT_AUTHENTICATED", "Admin sign-in required.");
        }

        var record = rosterUserFor(admin) || MockDB.findUser("admin@gmail.com");
        var currentId = record ? record.id : (admin.userId || 0);
        return { user: publicUser(updateProfile(
            body, currentId, { userId: admin.userId, name: admin.name,
                email: admin.email, role: "ADMIN", status: "Active" })) };
    } },

    { pattern: /^\/api\/auth\/login$/, handle: function (match, params, body) {
        body = body || {};
        var loginId = String(body.loginId || "").trim();
        var user = MockDB.findUser(loginId);

        /* Mock parity with POST /api/payments/verify (which always succeeds):
           an identifier that is not in the roster still returns a session, so
           a fresh browser can walk the demo without registering first. The
           real API answers 401 INVALID_CREDENTIALS after the BCrypt compare —
           same { token, user } body on success, same ApiError shape on failure,
           so login.html's .catch() path already handles it. */
        if (!user) {
            user = {
                id: 0,
                name: "Demo Traveller",
                email: loginId.indexOf("@") !== -1 ? loginId : "",
                phone: digitsOf(loginId),
                role: "USER",
                status: "Active",
                registeredAt: new Date().toISOString()
            };
        }

        return { token: mockJwt(user), user: publicUser(user) };
    } },

    /* POST /api/admin/flights — create (fix plan §12; execution step 5). Returns the bare flight
       object, which is what FlightController.create returns. */
    { pattern: /^\/api\/admin\/flights$/, handle: function (match, params, body) {
        var record = mockFlightBody(body);
        assertFlightNoFree(record.no);
        return MockDB.addFlight(record);
    } }
];

/* =====================================================
   PUT / DELETE — the write verbs the admin panel needs
   (fix plan §12 steps 4–5 need these; execution step 3, added 2026-09-18)

   These tables exist BEFORE any page uses them. `api.js` exposed only
   apiGet/apiPost until now, which is why every admin module wrote straight
   into its OWN localStorage store (`admin-flights.js` keeps `SEED_FLIGHTS`
   and its own `save()`) — an Edit or a Delete had no client verb to call at
   all, so "call the real PUT endpoint" was not implementable rather than
   merely unimplemented.

   Each module's routes are added HERE as that module is migrated off its
   private store, so a handler always ships beside the page that calls it
   (fix-plan §12 execution step 5 does the Flights module that way). An unmapped route is not
   silent: `mockRequest` below answers 404 NOT_IMPLEMENTED.
   ===================================================== */
var MOCK_PUT_ROUTES = [
    /* PUT /api/admin/flights/{id} — the edit, and the Activate/Disable toggle
       (which is the same write with one field changed). The id comes from the
       path, never the body: it is the entity's identity, not a page field. */
    { pattern: /^\/api\/admin\/flights\/(\d+)$/, handle: function (match, params, body) {
        var id = Number(match[1]);
        if (!MockDB.findFlight(id)) {
            throw new ApiError(404, "NOT_FOUND", "No flight with id " + id);
        }

        var record = mockFlightBody(body);
        assertFlightNoFree(record.no, id);

        var updated = MockDB.updateFlight(id, record);
        return updated || MockDB.findFlight(id); // bookedSeats etc. survive the patch
    } }
];

var MOCK_DELETE_ROUTES = [
    /* DELETE /api/admin/flights/{id}. A flight with bookings is a 409 naming the
       count and pointing at Inactive — the same refusal, wording and code as
       ConflictException.flightHasBookings, so the page's toast shows the real
       reason instead of a generic failure. Answers null (204 has no body). */
    { pattern: /^\/api\/admin\/flights\/(\d+)$/, handle: function (match) {
        var id = Number(match[1]);
        var flight = MockDB.findFlight(id);
        if (!flight) {
            throw new ApiError(404, "NOT_FOUND", "No flight with id " + id);
        }

        var bookings = MockDB.bookingsForFlight(flight.no);
        if (bookings > 0) {
            throw new ApiError(409, "FLIGHT_HAS_BOOKINGS",
                "Flight " + flight.no + " has " + bookings + " booking(s) and cannot be deleted. "
                + "Set its status to Inactive instead, so existing bookings stay valid.");
        }

        MockDB.deleteFlight(id);
        return null;
    } }
];

function mockRequest(method, path, body) {
    var qIndex = path.indexOf("?");
    var route = (qIndex === -1 ? path : path.slice(0, qIndex)).replace(/\/+$/, "");
    var params = new URLSearchParams(qIndex === -1 ? "" : path.slice(qIndex + 1));

    /* Method → table. A verb with no table yet still resolves, to a 404 below:
       loudly unimplemented, never a silent no-op. */
    var table = method === "POST" ? MOCK_POST_ROUTES
        : method === "PUT" ? MOCK_PUT_ROUTES
        : method === "DELETE" ? MOCK_DELETE_ROUTES
        : MOCK_GET_ROUTES;
    for (var i = 0; i < table.length; i++) {
        var match = route.match(table[i].pattern);
        if (match) {
            try {
                return Promise.resolve(table[i].handle(match, params, body));
            } catch (err) {
                return Promise.reject(err instanceof ApiError ? err :
                    new ApiError(500, "MOCK_ERROR", err && err.message ? err.message : String(err)));
            }
        }
    }
    return Promise.reject(new ApiError(404, "NOT_IMPLEMENTED",
        "No mock handler for " + method + " " + route + " — see api.js route table"));
}

/* =====================================================
   Real mode — fetch against API_BASE_URL (Phase 3)
   ===================================================== */

async function httpRequest(method, path, body) {
    var headers = { "Accept": "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";

    /* §3.3: attach the session token auth.js stored (mock: unsigned JWT;
       Phase 3: the real signed JWT). Mock mode never reaches this branch. */
    var tokenKey = (typeof YATRA_CONFIG !== "undefined" && YATRA_CONFIG.AUTH_TOKEN_KEY)
        || "yatra_auth_token";
    var authToken = sessionStorage.getItem(tokenKey);
    if (authToken) headers["Authorization"] = "Bearer " + authToken;

    var res;
    try {
        res = await fetch(API_BASE_URL + path, {
            method: method,
            headers: headers,
            body: body !== undefined ? JSON.stringify(body) : undefined
        });
    } catch (err) {
        throw new ApiError(0, "NETWORK_ERROR", "Could not reach the API at " + API_BASE_URL);
    }

    var text = await res.text();
    var data = null;
    if (text) {
        try { data = JSON.parse(text); } catch (err) { /* non-JSON body */ }
    }
    if (!res.ok) {
        throw new ApiError(res.status,
            (data && data.error) || "HTTP_" + res.status,
            (data && data.message) || res.statusText || "Request failed");
    }
    return data;
}

/* =====================================================
   Public surface — the only API functions pages may call
   ===================================================== */

async function apiGet(path) {
    if (USE_MOCK_DATA) return mockRequest("GET", path);
    return httpRequest("GET", path);
}

async function apiPost(path, body) {
    if (USE_MOCK_DATA) return mockRequest("POST", path, body);
    return httpRequest("POST", path, body);
}

/* The two verbs the admin CRUD depends on. Same shape as apiGet/apiPost, so a
   page's call site reads identically in mock and real mode —
   `apiPut('/api/admin/flights/' + id, payload)` and `apiDelete(...)` — and
   `httpRequest` already attaches the Bearer token it was always passed. */
async function apiPut(path, body) {
    if (USE_MOCK_DATA) return mockRequest("PUT", path, body);
    return httpRequest("PUT", path, body);
}

async function apiDelete(path, body) {
    if (USE_MOCK_DATA) return mockRequest("DELETE", path, body);
    return httpRequest("DELETE", path, body);
}
