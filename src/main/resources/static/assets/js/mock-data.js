/* =====================================================
   YATRA 2.0 — MOCK DATA LAYER (spec §35 "mock-data.js")
   Single source of truth for ALL mock data:

   - The browser "database": every domain localStorage
     store (airlines, destinations, bookings) is
     read/written ONLY here — page JS never touches
     localStorage for domain data (dynamic-readiness
     rule, §47).
   - The flight catalogue: the 11-airport map, the 4
     Nepali carriers, fare classes + policies, schedules
     and the deterministic per-date flight generator
     (moved here from searchFlight.js, which no longer
     hardcodes flights — spec rule: no hardcoded flight
     data in page JS).

   Mock responses mirror the §36 API contract so the
   Spring backend can later return the same shapes.
   ===================================================== */

var MockDB = (function () {
    "use strict";

    /* =====================================================
       1. Browser "database" helpers (the only localStorage
          access in the frontend data layer)
       ===================================================== */

    function lsGet(key, fallback) {
        try {
            var raw = localStorage.getItem(key);
            return raw ? JSON.parse(raw) : fallback;
        } catch (err) {
            return fallback; // corrupted JSON → treat as empty
        }
    }

    function lsSet(key, value) {
        try {
            localStorage.setItem(key, JSON.stringify(value));
            return true;
        } catch (err) {
            return false; // quota exceeded — caller decides
        }
    }

    function round2(n) {
        return Math.round(n * 100) / 100;
    }

    /* =====================================================
       2. Stores (keys unchanged — existing pages and the
          admin panel keep working; data ownership moves here)
       ===================================================== */

    var KEYS = {
        airlines: "yatra_admin_airlines",
        destinations: "yatra_admin_destinations",
        bookings: "yatra_bookings",
        flights: "yatra_admin_flights",             // owned HERE since fix-plan §5 (2026-09-18) — self-seeds and holds the CRUD helpers the admin page reaches through api.js
        users: "yatra_admin_users",                 // shared roster (seeds here) — CRUD UI in admin-users.js
        bookingsSeedFlag: "yatra_bookings_seeded_v1" // owned by admin-bookings.js — read-only here
    };

    /* ---------- Airlines (self-seeds on first read; mirrors admin-airlines.js) ---------- */
    var SEED_AIRLINES = [
        { id: 1, name: "Buddha Air", iata: "U4", description: "Nepal's largest domestic airline by fleet and destinations, operating ATR 42/72 aircraft.", status: "Active", logo: "assets/imgs/airline-buddha.jpg" },
        { id: 2, name: "Yeti Airlines", iata: "YT", description: "Major domestic carrier connecting Kathmandu with Pokhara and other regional airports.", status: "Active", logo: "assets/imgs/airline-yeti.jpg" },
        { id: 3, name: "Shree Airlines", iata: "S3", description: "Operates domestic routes and helicopter services across Nepal.", status: "Active", logo: "assets/imgs/airline-shree.svg" },
        { id: 4, name: "Sita Air", iata: "ST", description: "Domestic airline serving scheduled and charter flights with Dornier and ATR aircraft.", status: "Active", logo: "assets/imgs/airline-sita.jpeg" }
    ];

    function getAirlines() {
        var data = lsGet(KEYS.airlines, null);
        if (Array.isArray(data)) {
            // Migration (2026-09-15): roster trimmed to 4 carriers — drop
            // Summit Air (IATA RM) from stores saved before the removal.
            var cleaned = data.filter(function (a) { return a && a.iata !== "RM"; });
            if (cleaned.length !== data.length) lsSet(KEYS.airlines, cleaned);
            return cleaned;
        }
        lsSet(KEYS.airlines, SEED_AIRLINES);
        return SEED_AIRLINES.slice();
    }

    /* ---------- Destinations (self-seeds on first read; mirrors admin-destinations.js) ---------- */
    var SEED_DESTINATIONS = [
        { id: 1, city: "Kathmandu", code: "KTM", airport: "Tribhuvan International Airport", description: "Nepal's capital and the hub of the domestic network — nearly every route connects here.", imageUrl: "assets/imgs/header-top-image.jpg", status: "Active" },
        { id: 2, city: "Pokhara", code: "PKR", airport: "Pokhara International Airport", description: "Gateway to the Annapurnas and Phewa Lake — the busiest tourist route in the country.", imageUrl: "assets/imgs/pokhara.jpg", status: "Active" },
        { id: 3, city: "Biratnagar", code: "BIR", airport: "Biratnagar Airport", description: "Eastern metropolis and industrial hub of the Terai plains.", imageUrl: "assets/imgs/biratnagar.png", status: "Active" },
        { id: 4, city: "Bhairahawa", code: "BWA", airport: "Gautam Buddha International Airport", description: "Home of Lumbini, the birthplace of Buddha — the newest international gateway.", imageUrl: "assets/imgs/bhairahawa.jpeg", status: "Active" },
        { id: 5, city: "Nepalgunj", code: "KEP", airport: "Nepalgunj Airport", description: "Western Terai hub and the jumping-off point for remote western Nepal.", imageUrl: "assets/imgs/nepalgunj.jpeg", status: "Active" },
        { id: 6, city: "Bharatpur", code: "BHR", airport: "Bharatpur Airport", description: "Chitwan city serving Sauraha and the national-park safari circuit.", imageUrl: "assets/imgs/mountainview.jpg", status: "Active" },
        { id: 7, city: "Bhadrapur", code: "BDP", airport: "Bhadrapur Airport", description: "Eastern gateway to Ilam's tea gardens and the taplejung trailheads.", imageUrl: "", status: "Active" },
        { id: 8, city: "Janakpur", code: "JKR", airport: "Janakpur Airport", description: "Historic Mithila city and pilgrimage centre of Sita Janaki.", imageUrl: "", status: "Active" },
        { id: 9, city: "Simara", code: "SIM", airport: "Simara Airport", description: "Bara district airport serving the industrial corridor south of Kathmandu.", imageUrl: "", status: "Active" },
        { id: 10, city: "Dhangadhi", code: "DHI", airport: "Dhangadhi Airport", description: "Far-western commercial hub and gateway to Sudurpashchim.", imageUrl: "", status: "Active" },
        { id: 11, city: "Tumlingtar", code: "TMI", airport: "Tumlingtar Airport", description: "Eastern hill-town airport on the Arun valley route.", imageUrl: "", status: "Active" }
    ];

    function getDestinations() {
        var data = lsGet(KEYS.destinations, null);
        if (Array.isArray(data)) return data;
        lsSet(KEYS.destinations, SEED_DESTINATIONS);
        return SEED_DESTINATIONS.slice();
    }

    /* ---------- Bookings (NO seed here — live records only) ----------
       The store is written by esewaConfirm.js on PAY success and seeded
       once by admin-bookings.js (flag KEYS.bookingsSeedFlag). This layer
       is the read/write path; it never invents demo rows. */
    function getBookings() {
        var data = lsGet(KEYS.bookings, []);
        return Array.isArray(data) ? data : [];
    }

    function getBookingById(id) {
        var list = getBookings();
        for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].id === id) return list[i];
        }
        return null;
    }

    /* Write path for the payments flow (POST /api/payments/verify —
       wired to esewaConfirm.js in the item-16 continuation). */
    function addBooking(record) {
        var list = getBookings();
        list.unshift(record); // newest first, same as esewaConfirm.js always did
        return lsSet(KEYS.bookings, list);
    }

    /* Booked-seat increment on checkout (the admin-flights rule stays
       intact: seats capacity is stored, bookedSeats counts real checkouts,
       available = capacity − booked is always derived, never stored).
       Returns the new booked count, or null when there is no matching
       flight row. Same matching rule esewaConfirm.js used (no + date). */
    function incrementBookedSeats(flightNo, from, to, count) {
        var flights = getFlights(); // seeded on first read — was a raw key read
        for (var i = 0; i < flights.length; i++) {
            var f = flights[i];
            if (f && f.no === flightNo && f.from === from && f.to === to) {
                if (typeof f.bookedSeats !== "number") return null;
                f.bookedSeats = Math.min(f.seats || 0, f.bookedSeats + (count || 1));
                lsSet(KEYS.flights, flights);
                return f.bookedSeats;
            }
        }
        return null;
    }

    /* =====================================================
       3. Flight catalogue (moved verbatim from searchFlight.js
          so the listing page has zero hardcoded flight data)
       ===================================================== */

    /* ---------- Airports (11-code map — admin-destinations enforces this set) ---------- */
    var AIRPORTS = {
        KTM: { city: "Kathmandu", airport: "Tribhuvan International Airport" },
        PKR: { city: "Pokhara", airport: "Pokhara International Airport" },
        BWA: { city: "Bhairahawa", airport: "Gautam Buddha International Airport" },
        BDP: { city: "Bhadrapur", airport: "Bhadrapur Airport" },
        BIR: { city: "Biratnagar", airport: "Biratnagar Airport" },
        BHR: { city: "Bharatpur", airport: "Bharatpur Airport" },
        JKR: { city: "Janakpur", airport: "Janakpur Airport" },
        SIM: { city: "Simara", airport: "Simara Airport" },
        DHI: { city: "Dhangadhi", airport: "Dhangadhi Airport" },
        KEP: { city: "Nepalgunj", airport: "Nepalgunj Airport" },
        TMI: { city: "Tumlingtar", airport: "Tumlingtar Airport" }
    };

    /* ---------- Nationality labels (the home search's Country select) ----------
       Kept in the data layer so booking.html's passenger form (passenger
       nationality) and the pages after it read ONE map instead of each
       carrying a private copy (item 17 de-duplication, Session 31). */
    var NATIONALITIES = {
        NP: "Nepal",
        IN: "India",
        US: "USA",
        UK: "UK"
    };

    /* ---------- Demo flight (direct-open fallback) ----------
       Used only when a wizard page is opened without `yatra_selected_flight`
       (the numbers booking.html's sidebar used to hardcode). It lives here so
       page JS never carries flight data of its own — dynamic-readiness rule,
       §47. Any real search/selection overwrites it. */
    var DEMO_FLIGHT = {
        from: "KTM", to: "BWA", date: "2026-10-01",
        flightNo: "U4 951", depart: "07:15", arrive: "07:45",
        airline: { name: "Buddha Air", code: "U4" },
        flightClass: "E Class", refundable: false,
        price: 6300, passengers: 1
    };

    /* ---------- Payable total ----------
       The ONE rule for "what the customer owes": fare × paying passengers, as
       persisted in bookingData.flight.totalPrice (§34). The gateway steps read
       it through here so the amount shown, charged and stored can never drift
       apart; the backend's PaymentService computes the same thing from the
       booking row. Falls back to the selected flight's single fare, then to the
       demo fare, so an opened-directly page still renders. */
    function payableTotal(booking, flight) {
        var f = (booking && booking.flight) || {};
        if (typeof f.totalPrice === "number" && f.totalPrice > 0) return round2(f.totalPrice);

        var perPax = f.pricePerPassenger;
        var count = f.passengerCount;
        if (typeof perPax === "number" && perPax > 0) {
            return round2(perPax * (typeof count === "number" && count > 0 ? count : 1));
        }

        if (flight && typeof flight.price === "number" && flight.price > 0) {
            var paying = typeof flight.passengers === "number" && flight.passengers > 0
                ? flight.passengers : 1;
            return round2(flight.price * paying);
        }

        return 8299.99; // last-resort demo fare (same value the mock gateway used)
    }

    /* Short uppercase display labels used by the results header
       (moved verbatim from searchFlight.js CITY_UP so page text
       stays identical). */
    var AIRPORT_LABELS = {
        KTM: "KATHMANDU", PKR: "POKHARA", BWA: "BHAIRAHAWA (GAUTAM BUDDHA)", BDP: "BHADRAPUR (JHAPA)",
        BIR: "BIRATNAGAR", BHR: "BHARATPUR", JKR: "JANAKPUR", SIM: "SIMARA",
        DHI: "DHANGADHI", KEP: "NEPALGUNJ", TMI: "TUMLINGTAR"
    };

    /* ---------- Fare classes (Buddha-Air-style tiers) ---------- */
    var FARE_CLASSES = [
        { code: "E", label: "E Class", delta: 0, refundable: false },
        { code: "C", label: "C Class", delta: 1000, refundable: false },
        { code: "D", label: "D Class", delta: 2000, refundable: false },
        { code: "B", label: "B Class", delta: 3000, refundable: false },
        { code: "A", label: "A Class", delta: 4000, refundable: true },
        { code: "Y", label: "Y Class", delta: 5000, refundable: true }
    ];

    var FARE_POLICIES = {
        nonRefundable: [
            "Cancellation is not available for this fare.",
            "Only PSC (Airport Tax) will be refunded.",
            "Date changes are not permitted on this fare."
        ],
        refundable: [
            "Cancellation allowed up to 2 hours before departure — only a 10% fee (33.33% within 11 hours).",
            "Free date changes up to 2 hours before departure (fare difference may apply).",
            "Unutilized PSC (Airport Tax) is always refundable on request."
        ]
    };

    /* ---------- Daily schedule template ---------- */
    var SCHEDULES = [
        ["06:50", "07:35"], ["09:55", "10:40"], ["11:35", "12:20"],
        ["12:50", "13:35"], ["15:10", "15:55"], ["17:25", "18:10"]
    ];

    function minutesBetween(dep, arr) {
        var a = dep.split(":"), b = arr.split(":");
        return (+b[0] * 60 + +b[1]) - (+a[0] * 60 + +a[1]);
    }

    function fareOptions(baseFare) {
        return FARE_CLASSES.map(function (c) {
            return {
                code: c.code,
                label: c.label,
                delta: c.delta,
                price: round2(baseFare + c.delta),
                refundable: c.refundable
            };
        });
    }

    /* =====================================================
       4. GET /api/flights/search — mock handler
          (?origin=&destination=&date=&passengers=)

          Deterministic per-date generation, same math
          searchFlight.js used (4–6 flights, carrier mix and
          flight numbers vary by weekday/day-of-month) so the
          listing looks identical to before — the data just
          comes from here now.
       ===================================================== */

    function searchFlights(params) {
        params = params || {};
        var from = String(params.origin || "").toUpperCase();
        var to = String(params.destination || "").toUpperCase();
        var date = params.date || new Date().toISOString().slice(0, 10);
        var passengers = parseInt(params.passengers, 10) || 1;

        function endpoint(code) {
            var known = AIRPORTS[code];
            return {
                code: code,
                city: known ? known.city : code,
                airport: known ? known.airport : "",
                label: AIRPORT_LABELS[code] || code
            };
        }

        var flights = [];
        if (from && to && from !== to && AIRPORTS[from] && AIRPORTS[to]) {
            var d = new Date(date + "T00:00:00");
            if (!isNaN(d)) {
                var dow = d.getDay(), dom = d.getDate();
                var count = 4 + ((dow + dom) % 3); // 4–6 flights
                var roster = getAirlines();
                for (var i = 0; i < count; i++) {
                    var s = SCHEDULES[i % SCHEDULES.length];
                    var al = roster[(i * 2 + dow + dom) % roster.length];
                    var base = round2(8299.99 + i * 150);
                    flights.push({
                        flightNo: al.iata + " " + (951 + i * 7 + dow),
                        airline: { name: al.name, code: al.iata, logo: al.logo },
                        from: from,
                        to: to,
                        date: date,
                        depart: s[0],
                        arrive: s[1],
                        durationMinutes: minutesBetween(s[0], s[1]),
                        baseFare: base,
                        fareOptions: fareOptions(base),
                        baggage: "15kg",
                        handCarry: "5kg",
                        isLowest: i === 0,
                        // strike-through price shown next to the low fare
                        comparePrice: i === 0 ? round2(base + 177) : null
                    });
                }
            }
        }

        return {
            origin: endpoint(from),
            destination: endpoint(to),
            date: date,
            passengers: passengers,
            farePolicies: FARE_POLICIES,
            flights: flights
        };
    }

    /* =====================================================
       5. Derived values shared with the backend plan
       ===================================================== */

    /* Teacher-flagged rule: available = capacity − booked,
       always computed, never stored or edited. */
    function seatsAvailable(flight) {
        if (!flight) return 0;
        return Math.max(0, (flight.seats || 0) - (flight.bookedSeats || 0));
    }

    /* The same deterministic PNR/ticket-number derivation
       eticket.js and esewaConfirm.js use, so admin records
       always match the printed e-ticket. */
    function derivePnrTicket(txnId) {
        var seed = String(txnId || "").replace(/\D/g, "") || "00000000";
        var pnr = "YTRA" + seed.slice(0, 2).split("").map(function (c) {
            return String.fromCharCode(65 + (+c) % 26);
        }).join("") + seed.slice(2, 4);
        return { pnr: pnr, ticketNo: "784-24" + seed.slice(0, 10) };
    }

    /* ---------- Flights (self-seeds on first read) ----------
       Moved out of admin-flights.js in fix-plan §5 (2026-09-18). The page used to
       own this store outright — its own STORAGE_KEY (this same key), its own
       SEED_FLIGHTS, its own load()/save() — so a Save wrote to localStorage and
       the API was never called. Ownership sits here now, beside the other stores,
       and the page reaches it only through apiPost/apiPut/apiDelete. The six seed
       rows below are the page's, moved verbatim: a fresh browser still shows the
       same schedule, and any already-saved rows are untouched. */
    var SEED_FLIGHTS = [
        {
            id: 1, no: "U4 951", airlineId: 1, from: "KTM", to: "PKR",
            dep: "06:50", arr: "07:35", aircraft: "ATR 72", fare: 8299.99,
            seats: 70, bookedSeats: 4, status: "Active"
        },
        {
            id: 2, no: "YT 958", airlineId: 2, from: "KTM", to: "PKR",
            dep: "09:55", arr: "10:40", aircraft: "ATR 72", fare: 8449.99,
            seats: 70, bookedSeats: 11, status: "Active"
        },
        {
            id: 3, no: "S3 507", airlineId: 3, from: "KTM", to: "BIR",
            dep: "11:35", arr: "12:20", aircraft: "CRJ 700", fare: 8899.99,
            seats: 78, bookedSeats: 6, status: "Active"
        },
        {
            id: 4, no: "ST 221", airlineId: 4, from: "KTM", to: "KEP",
            dep: "12:50", arr: "13:45", aircraft: "Dornier 228", fare: 9199.99,
            seats: 19, bookedSeats: 2, status: "Active"
        },
        {
            id: 5, no: "U4 953", airlineId: 1, from: "KTM", to: "BWA",
            dep: "15:10", arr: "16:00", aircraft: "ATR 72", fare: 8799.99,
            seats: 70, bookedSeats: 0, status: "Active"
        },
        {
            id: 6, no: "YT 962", airlineId: 2, from: "PKR", to: "KTM",
            dep: "17:25", arr: "18:10", aircraft: "ATR 42", fare: 8299.99,
            seats: 46, bookedSeats: 9, status: "Active"
        }
    ];

    function getFlights() {
        var data = lsGet(KEYS.flights, null);
        if (Array.isArray(data)) return data;
        lsSet(KEYS.flights, SEED_FLIGHTS);
        return SEED_FLIGHTS.slice();
    }

    /* ---------- Flights, the write half (fix-plan §5) ----------
       The mutations the admin page now performs through api.js. The rules mirror
       FlightService because the mock is the API's specification:
         - the id is minted here — the page never sends one (it is the entity's
           identity, and the real column is AUTO_INCREMENT);
         - a flight number is canonicalised before any comparison, the same
           normalisation FlightService.normalizeFlightNo applies, because
           flight_no is case-sensitive in MySQL and "u4 951" is otherwise a
           different row from "U4 951";
         - bookedSeats is NOT writable — it counts checkouts, never an edit;
         - deleting a flight that has bookings is refused by the route table (409,
           the same ConflictException the service throws), which is what
           bookingsForFlight() is for. */
    function normalizeFlightNo(value) {
        return String(value == null ? "" : value)
            .trim().replace(/\s+/g, " ").toUpperCase();
    }

    function findFlight(id) {
        var list = getFlights();
        for (var i = 0; i < list.length; i++) {
            if (list[i] && String(list[i].id) === String(id)) return list[i];
        }
        return null;
    }

    function addFlight(record) {
        var list = getFlights();
        var nextId = list.reduce(function (max, f) {
            var id = Number(f && f.id) || 0;
            return id > max ? id : max;
        }, 0) + 1;

        record = record || {};
        record.id = nextId;
        record.bookedSeats = 0; // a flight that was just created cannot have one
        list.push(record);
        lsSet(KEYS.flights, list);
        return record;
    }

    function updateFlight(id, patch) {
        patch = patch || {};
        var list = getFlights();
        for (var i = 0; i < list.length; i++) {
            if (!list[i] || String(list[i].id) !== String(id)) continue;

            for (var key in patch) {
                if (!Object.prototype.hasOwnProperty.call(patch, key)) continue;
                if (key === "id" || key === "bookedSeats") continue; // system data
                list[i][key] = patch[key];
            }
            lsSet(KEYS.flights, list);
            return list[i];
        }
        return null;
    }

    function deleteFlight(id) {
        var list = getFlights();
        var removed = null;
        var kept = list.filter(function (f) {
            if (f && String(f.id) === String(id)) { removed = f; return false; }
            return true;
        });
        if (!removed) return null;
        lsSet(KEYS.flights, kept);
        return removed;
    }

    /* How many live bookings reference this flight number — the delete guard's
       test, mirroring FlightService.deleteFlight's countByFlightId. Bookings are
       matched the way the verify handler stores them (booking.flight.flightNo). */
    function bookingsForFlight(flightNo) {
        var target = normalizeFlightNo(flightNo);
        return getBookings().filter(function (b) {
            return b && b.flight && normalizeFlightNo(b.flight.flightNo) === target;
        }).length;
    }

    /* ---------- Users (self-seeds on first read) ----------
       ONE roster shared by every entry point: signup (POST /api/auth/register),
       login (POST /api/auth/login) and the admin panel's user management page.
       User #1 mirrors the mock admin session admin-login.html writes
       (userId 1, admin@gmail.com) so the two sides agree.
       No password field exists here — no mock user ever stores a credential;
       hashing belongs to the backend's BCryptPasswordEncoder (Master Plan §3.4,
       and admin-users.html's own "no password field" rule). */
    var SEED_USERS = [
        { id: 1, name: "Dikshya Ghising", email: "admin@gmail.com", phone: "9800000001", role: "ADMIN", status: "Active", registeredAt: "2026-08-01T09:00:00.000Z" },
        { id: 2, name: "Anju Karki", email: "anju.karki@example.com", phone: "9841234567", role: "USER", status: "Active", registeredAt: "2026-09-01T10:20:00.000Z" },
        { id: 3, name: "Bikash Shrestha", email: "bikash.s@example.com", phone: "9818765432", role: "USER", status: "Active", registeredAt: "2026-09-05T14:45:00.000Z" },
        { id: 4, name: "Sanjay Thapa Magar", email: "sanjay.tm@example.com", phone: "9801122334", role: "USER", status: "Inactive", registeredAt: "2026-09-06T08:10:00.000Z" },
        { id: 5, name: "Prakriti Rai", email: "prakriti.rai@example.com", phone: "9856234781", role: "USER", status: "Active", registeredAt: "2026-09-08T16:30:00.000Z" },
        { id: 6, name: "Nirmala Gurung", email: "nirmala.g@example.com", phone: "9845612309", role: "USER", status: "Active", registeredAt: "2026-09-09T11:05:00.000Z" },
        { id: 7, name: "Dipesh Neupane", email: "dipesh.n@example.com", phone: "9861250974", role: "USER", status: "Active", registeredAt: "2026-09-10T19:40:00.000Z" },
        { id: 8, name: "Sarita Lamichhane", email: "sarita.l@example.com", phone: "9849056172", role: "USER", status: "Inactive", registeredAt: "2026-09-11T07:55:00.000Z" },
        { id: 9, name: "Kabin Bhandari", email: "kabin.b@example.com", phone: "9807341285", role: "USER", status: "Active", registeredAt: "2026-09-12T13:15:00.000Z" },
        { id: 10, name: "Manish Pokhrel", email: "manish.p@example.com", phone: "9812345678", role: "USER", status: "Active", registeredAt: "2026-09-13T17:25:00.000Z" }
    ];

    function getUsers() {
        var data = lsGet(KEYS.users, null);
        if (Array.isArray(data) && data.length) return data;
        lsSet(KEYS.users, SEED_USERS);
        return SEED_USERS.slice();
    }

    /* Mobile numbers are stored AND matched in their 10-digit national form:
       "+977 9800-000-001" and "9800000001" are the same person. The signup
       page's +977 dial prefix and the login page's free-text field both feed
       this, and the backend's unique constraint sees one canonical value. */
    function normalizePhone(value) {
        var digits = String(value == null ? "" : value).replace(/\D/g, "");
        if (digits.length === 13 && digits.slice(0, 3) === "977") digits = digits.slice(3);
        return digits;
    }

    /* Registration path (POST /api/auth/register): appends to the same roster
       the admin panel manages, so a new customer appears in admin-users.html
       right away — one store, one record shape. */
    function addUser(user) {
        var users = getUsers(); // seeds first — the roster is never empty
        user = user || {};

        var nextId = 1;
        users.forEach(function (u) {
            var id = Number(u && u.id) || 0;
            if (id >= nextId) nextId = id + 1;
        });

        var record = {
            id: nextId,
            name: user.name || "Unnamed",
            email: user.email || "",
            phone: normalizePhone(user.phone),
            role: user.role || "USER",
            status: user.status || "Active",
            registeredAt: user.registeredAt || new Date().toISOString()
        };
        users.push(record);
        lsSet(KEYS.users, users);
        return record;
    }

    /* Identifier lookup used by login and by register's uniqueness check:
       email (case-insensitive) or mobile digits. Always checks the live
       roster, never a cached copy. */
    function findUser(identifier) {
        var needle = String(identifier == null ? "" : identifier).trim().toLowerCase();
        var needleDigits = normalizePhone(needle);
        if (!needle) return null;

        var users = getUsers();
        for (var i = 0; i < users.length; i++) {
            var u = users[i] || {};
            if (String(u.email || "").toLowerCase() === needle) return u;
            if (needleDigits && normalizePhone(u.phone) === needleDigits) return u;
        }
        return null;
    }

    /* Same match as findUser(), but ignoring one record — the uniqueness check
       an UPDATE needs: "is this email taken by somebody ELSE?". Without the
       exclude, saving a profile form would always collide with the record
       being edited. Mirrors the backend's unique constraint + "@Id exclude". */
    function findUserExcluding(identifier, excludeId) {
        var found = findUser(identifier);
        if (!found) return null;
        return (Number(found.id) === Number(excludeId)) ? null : found;
    }

    /* Profile update path (PUT/POST /api/users/me and /api/admin/profile).
       Only the fields a profile form owns are writable — never role, status
       or the id. Returns the updated record, or null when the id is unknown.
       The API layer owns the uniqueness check (it is the one that must return
       409 EMAIL_EXISTS / PHONE_EXISTS), so this stays a plain store write. */
    function updateUser(id, patch) {
        patch = patch || {};
        var users = getUsers();
        var index = -1;
        for (var i = 0; i < users.length; i++) {
            if (Number((users[i] || {}).id) === Number(id)) { index = i; break; }
        }
        if (index === -1) return null;

        var current = users[index];
        var next = {};
        for (var key in current) {
            if (Object.prototype.hasOwnProperty.call(current, key)) next[key] = current[key];
        }
        if (patch.name != null) next.name = String(patch.name).trim() || next.name;
        if (patch.email != null) next.email = String(patch.email).trim();
        if (patch.phone != null) next.phone = normalizePhone(patch.phone);

        users[index] = next;
        lsSet(KEYS.users, users);
        return next;
    }

    /* =====================================================
       7. Public surface
       ===================================================== */
    return {
        KEYS: KEYS,
        AIRPORTS: AIRPORTS,
        NATIONALITIES: NATIONALITIES,
        DEMO_FLIGHT: DEMO_FLIGHT,
        SEED_USERS: SEED_USERS,
        FARE_CLASSES: FARE_CLASSES,
        FARE_POLICIES: FARE_POLICIES,

        getAirlines: getAirlines,
        getDestinations: getDestinations,
        getBookings: getBookings,
        getBookingById: getBookingById,
        getFlights: getFlights,
        normalizeFlightNo: normalizeFlightNo,
        findFlight: findFlight,
        addFlight: addFlight,
        updateFlight: updateFlight,
        deleteFlight: deleteFlight,
        bookingsForFlight: bookingsForFlight,
        getUsers: getUsers,
        addUser: addUser,
        findUser: findUser,
        findUserExcluding: findUserExcluding,
        updateUser: updateUser,
        normalizePhone: normalizePhone,
        addBooking: addBooking,
        incrementBookedSeats: incrementBookedSeats,
        payableTotal: payableTotal,
        searchFlights: searchFlights,
        seatsAvailable: seatsAvailable,
        derivePnrTicket: derivePnrTicket
    };
})();
