/* =====================================================
   YATRA 2.0 — FORM VALIDATION (spec §35 "validation.js")

   The one place every client-side rule lives (spec §35:
   "All forms must perform client-side validation before
   submission"):

     validateSearchForm()      homeLogged.html  → Book a Flight
     validatePassengerForm()   booking.html     → wizard step 2
     validateRegistration()    signup.html      → both panels
     validateLoginForm()       login.html       → wizard step 0

   Rules are pure helpers; ERROR PAINTING is pluggable so every
   page keeps the error look it already ships:

     errorStyle = "flash" (default)
        red border + focus ring on the control's wrapper
        (.input-wrapper / .control / .select-wrapper) — what
        booking.html and the home search do today. Those pages
        ship no .error-msg CSS, so nothing new is injected.

     errorStyle = "field"
        .field.has-error + <small class="error-msg"> — the
        convention login.html / signup.html style inline.
        Those two pages opt in right after this file loads.

   Every validator returns the same result object:

     { valid: true, errors: [], firstInvalid: null }
     { valid: false, errors: [{ field, message, el }], firstInvalid }

   so a page can simply check .valid, or surface errors[0].message.
   Regexes/date rules are NOT duplicated per page any more — the
   same EMAIL_RE / PHONE_RE / today() serve every form, which is
   what keeps the wizard and the auth pages agreeing.

   Phase 2 note: this file is pure frontend-side UX. The Spring
   backend re-validates everything (Bean Validation on the DTOs)
   because client-side validation is never a security boundary.
   ===================================================== */

var YatraValidation = (function () {
    "use strict";

    /* =====================================================
       1. Rules (pure — no DOM)
       ===================================================== */

    /* Same two patterns login.html / signup.html used inline before. */
    var EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
    var PHONE_RE = /^9\d{9}$/;   // Nepali mobile: 10 digits, starts with 9
    var PASSWORD_MIN = 8;        // mirrors signup.html's minlength="8"
    var MAX_PASSENGERS = 20;     // safety cap for the discovery loop

    function isEmail(v) {
        return EMAIL_RE.test(String(v == null ? "" : v).trim());
    }

    /* Normalize first: "+977 9803-660-660" and "9803660660" both pass. */
    function digits(v) {
        return String(v == null ? "" : v).replace(/\D/g, "");
    }

    function isNepaliMobile(v) {
        var d = digits(v);
        if (d.length === 13 && d.slice(0, 3) === "977") d = d.slice(3); // +977 prefix
        return PHONE_RE.test(d);
    }

    function isBlank(v) {
        return !String(v == null ? "" : v).trim();
    }

    function pad(n) {
        return (n < 10 ? "0" : "") + n;
    }

    /* Local YYYY-MM-DD — deliberately NOT toISOString(): "today" in UTC
       is already tomorrow in Nepal (UTC+05:45), which would reject a
       perfectly valid same-day departure. */
    function todayISO() {
        var d = new Date();
        return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
    }

    /* =====================================================
       2. Error painting (each page keeps its own look)
       ===================================================== */

    var api = {
        EMAIL_RE: EMAIL_RE,
        PHONE_RE: PHONE_RE,
        PASSWORD_MIN: PASSWORD_MIN,
        /** "flash" (default) | "field" — sees section header above. */
        errorStyle: "flash",

        isEmail: isEmail,
        isNepaliMobile: isNepaliMobile,
        isBlank: isBlank,
        todayISO: todayISO,

        clearFieldError: clearFieldError,
        clearFormErrors: clearFormErrors,

        validateSearchForm: validateSearchForm,
        validatePassengerForm: validatePassengerForm,
        validateRegistration: validateRegistration,
        validateLoginForm: validateLoginForm
    };

    function el(id) {
        return id ? document.getElementById(id) : null;
    }

    /* The visible control: borders live on the wrapper, not the input
       (homeLogged's .input-wrapper, signup/login's .control, booking's
       .select-wrapper) — falling back to the element itself. */
    function controlOf(field) {
        if (!field || !field.closest) return field;
        return field.closest(".input-wrapper, .control, .select-wrapper, .checkbox-row") || field;
    }

    function paintFlash(field) {
        var target = controlOf(field);
        if (!target || !target.style) return;
        var isCheckbox = field.type === "checkbox";
        var prev = {
            borderColor: target.style.borderColor,
            boxShadow: target.style.boxShadow,
            color: target.style.color
        };

        if (isCheckbox) target.style.color = "var(--accent)";
        else {
            target.style.borderColor = "var(--accent)";
            target.style.boxShadow = "0 0 0 4px rgba(230, 57, 70, 0.1)";
        }

        setTimeout(function () {
            target.style.borderColor = prev.borderColor;
            target.style.boxShadow = prev.boxShadow;
            target.style.color = prev.color;
        }, 2000);
    }

    function paintField(field, message) {
        var wrap = field.closest ? field.closest(".field") : null;
        if (!wrap) return paintFlash(field); // page has no .field markup

        clearFieldError(field);
        wrap.classList.add("has-error");

        var msg = document.createElement("small");
        msg.className = "error-msg";
        msg.id = (field.id || "field") + "-error";
        msg.textContent = message;
        wrap.appendChild(msg);
        field.setAttribute("aria-describedby", msg.id);
    }

    function paint(field, message) {
        if (!field || !field.setAttribute) return;
        field.setAttribute("aria-invalid", "true");
        if (api.errorStyle === "field") paintField(field, message);
        else paintFlash(field);
    }

    /** Drop one field's error — call from the page's input/change handler. */
    function clearFieldError(field) {
        if (!field) return;
        var wrap = field.closest ? field.closest(".field") : null;
        if (wrap) {
            wrap.classList.remove("has-error");
            var msg = wrap.querySelector(".error-msg");
            if (msg) msg.remove();
        }
        if (field.removeAttribute) {
            field.removeAttribute("aria-invalid");
            field.removeAttribute("aria-describedby");
        }
    }

    /** Drop every error inside a form/container (used before re-validating). */
    function clearFormErrors(scope) {
        var root = typeof scope === "string" ? el(scope) : (scope || document);
        if (!root || !root.querySelectorAll) return;

        Array.prototype.forEach.call(root.querySelectorAll("[aria-invalid]"), function (f) {
            clearFieldError(f);
        });
        Array.prototype.forEach.call(root.querySelectorAll(".error-msg"), function (m) {
            if (m.parentNode) m.parentNode.removeChild(m);
        });
        Array.prototype.forEach.call(root.querySelectorAll(".has-error"), function (f) {
            f.classList.remove("has-error");
        });
    }

    /* =====================================================
       3. Result accumulation
       ===================================================== */

    function result() {
        return { valid: true, errors: [], firstInvalid: null };
    }

    function fail(res, field, name, message) {
        res.valid = false;
        res.errors.push({ field: name, message: message, el: field || null });
        if (!res.firstInvalid && field) res.firstInvalid = field;
        paint(field, message);
    }

    /* Passenger blocks are rendered as p1Title, p1LastName, … — discover
       the count from the DOM so validatePassengerForm() needs no argument
       (spec §35 lists it with none). Callers that already know the paying
       count can pass it and skip the probing. */
    function discoverPassengers() {
        var n = 0;
        while (n < MAX_PASSENGERS &&
            (el("p" + (n + 1) + "Title") || el("p" + (n + 1) + "FirstName"))) {
            n++;
        }
        return n;
    }

    /* =====================================================
       4. validateSearchForm() — homeLogged.html "Book a Flight"
       =====================================================
       Native `required` already covers the empty fields in a browser;
       what only JS can catch is a same-city route and a return date
       that precedes the departure. Both are checked here, plus the
       emptiness cases for callers that submit programmatically. */
    function validateSearchForm(opts) {
        opts = opts || {};
        var res = result();

        var origin = el(opts.origin || "origin");
        var destination = el(opts.destination || "destination");
        var depDate = el(opts.depDate || "depDate");
        var retDate = el(opts.retDate || "retDate");
        var toggle = el(opts.roundTrip || "roundTripToggle");
        var isRoundTrip = !!(toggle && toggle.checked);
        var today = todayISO();

        if (origin && isBlank(origin.value)) {
            fail(res, origin, "origin", "Please select a departure city.");
        }
        if (destination && isBlank(destination.value)) {
            fail(res, destination, "destination", "Please select an arrival city.");
        }
        if (origin && destination && !isBlank(origin.value) && !isBlank(destination.value) &&
            origin.value === destination.value) {
            fail(res, destination, "destination", "Departure and arrival cities must be different.");
        }

        if (depDate) {
            if (isBlank(depDate.value)) {
                fail(res, depDate, "depDate", "Please choose a departure date.");
            } else if (depDate.value < today) {
                fail(res, depDate, "depDate", "Departure date cannot be in the past.");
            }
        }

        if (isRoundTrip && retDate) {
            if (isBlank(retDate.value)) {
                fail(res, retDate, "retDate", "Please choose a return date.");
            } else if (depDate && !isBlank(depDate.value) && retDate.value < depDate.value) {
                fail(res, retDate, "retDate", "Return date cannot be before the departure date.");
            }
        }

        return res;
    }

    /* =====================================================
       5. validatePassengerForm() — booking.html wizard step 2
       =====================================================
       Contact block + every rendered passenger block + the terms
       checkbox. Required set is unchanged from the inline code it
       replaces (title / first / last per passenger); the added value
       is format checking on contact phone + email, which previously
       accepted anything non-empty. */
    function validatePassengerForm(opts) {
        opts = opts || {};
        var res = result();

        var CONTACT = [
            { id: "contactTitle", label: "Courtesy Title" },
            { id: "contactLastName", label: "Last Name" },
            { id: "contactFirstName", label: "First Name" },
            { id: "contactPhone", label: "Contact Number" },
            { id: "contactEmail", label: "Email Address" }
        ];

        CONTACT.forEach(function (f) {
            var field = el(f.id);
            if (field && isBlank(field.value)) {
                fail(res, field, f.id, f.label + " is required.");
            }
        });

        var phone = el("contactPhone");
        if (phone && !isBlank(phone.value) && !isNepaliMobile(phone.value)) {
            fail(res, phone, "contactPhone", "Enter a 10-digit mobile number starting with 9.");
        }

        var email = el("contactEmail");
        if (email && !isBlank(email.value) && !isEmail(email.value)) {
            fail(res, email, "contactEmail", "Enter a valid email address (e.g. name@gmail.com).");
        }

        var PAYING_FIELDS = [
            { suffix: "Title", label: "Courtesy Title" },
            { suffix: "LastName", label: "Last Name" },
            { suffix: "FirstName", label: "First Name" }
        ];

        var count = (typeof opts.paying === "number" && opts.paying > 0)
            ? Math.min(opts.paying, MAX_PASSENGERS)
            : discoverPassengers();

        for (var n = 1; n <= count; n++) {
            PAYING_FIELDS.forEach(function (f) {
                var id = "p" + n + f.suffix; // forEach is synchronous — n is correct here
                var field = el(id);
                if (field && isBlank(field.value)) {
                    fail(res, field, id, "Passenger " + n + " " + f.label + " is required.");
                }
            });
        }

        /* Terms is part of the same submit — only required on pages that
           actually render it (booking.html does). */
        var terms = el(opts.termsCheck || "termsCheck");
        if (terms && !terms.checked) {
            fail(res, terms, "termsCheck", "Please accept the terms and conditions to continue.");
        }

        return res;
    }

    /* =====================================================
       6. validateRegistration() — signup.html (number + email panels)
       =====================================================
       Validates the ACTIVE panel by default; the page passes the
       submitted form explicitly. */
    function validateRegistration(opts) {
        opts = opts || {};
        var res = result();
        var form = opts.form ||
            document.querySelector(".signup-form.panel.active") ||
            document.querySelector(".signup-form");
        if (!form) return res;

        var today = todayISO();

        Array.prototype.forEach.call(form.querySelectorAll("input, select"), function (field) {
            clearFieldError(field);

            var value = String(field.value == null ? "" : field.value).trim();

            if (field.hasAttribute("required") && !value) {
                fail(res, field, field.id, "This field is required.");
                return;
            }
            if (!value) return; // optional + empty → nothing to check

            if (field.type === "email" && !isEmail(value)) {
                fail(res, field, field.id, "Enter a valid email address (e.g. name@gmail.com).");
            }
            if (field.classList.contains("js-phone") && !isNepaliMobile(value)) {
                fail(res, field, field.id, "Enter a 10-digit mobile number starting with 9.");
            }
            if (field.classList.contains("js-dob") && value > today) {
                fail(res, field, field.id, "Date of birth cannot be in the future.");
            }
        });

        var password = form.querySelector(".js-pw");
        var confirm = form.querySelector(".js-confirm");

        if (password && password.value) {
            if (password.value.length < PASSWORD_MIN) {
                fail(res, password, password.id, "Password must be at least " + PASSWORD_MIN + " characters.");
            }
            if (confirm && confirm.value && password.value !== confirm.value) {
                fail(res, confirm, confirm.id, "Passwords do not match.");
            }
        }

        return res;
    }

    /* =====================================================
       7. validateLoginForm() — login.html
       =====================================================
       Identifier is either an email or a Nepali mobile number —
       same two accepted shapes the backend's User lookup uses. */
    function validateLoginForm(opts) {
        opts = opts || {};
        var res = result();
        var form = opts.form || el(opts.formId || "loginForm");
        if (!form) return res;

        var ident = form.querySelector("#" + (opts.identId || "loginId")) || el("loginId");
        var password = form.querySelector("#" + (opts.passwordId || "loginPassword")) || el("loginPassword");

        if (ident) {
            var value = String(ident.value || "").trim();
            if (!value) {
                fail(res, ident, "loginId", "Email or phone number is required");
            } else if (value.indexOf("@") !== -1) {
                if (!isEmail(value)) fail(res, ident, "loginId", "Enter a valid email address (e.g. name@gmail.com)");
            } else if (!isNepaliMobile(value)) {
                fail(res, ident, "loginId", "Enter a 10-digit mobile number starting with 9");
            }
        }

        if (password && !password.value) {
            fail(res, password, "loginPassword", "Password is required");
        }

        return res;
    }

    return api;
})();

/* =====================================================
   §35 spec names — the frontend spec lists these four functions
   by name, so they are exposed as globals as well (the same
   convention config.js/api.js use for USE_MOCK_DATA and
   apiGet/apiPost). Pages may call either form.
   ===================================================== */

function validateSearchForm(opts) {
    return YatraValidation.validateSearchForm(opts);
}

function validatePassengerForm(opts) {
    return YatraValidation.validatePassengerForm(opts);
}

function validateRegistration(opts) {
    return YatraValidation.validateRegistration(opts);
}

function validateLoginForm(opts) {
    return YatraValidation.validateLoginForm(opts);
}
