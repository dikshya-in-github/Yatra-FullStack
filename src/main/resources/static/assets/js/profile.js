/* =========================================================
   YATRA 2.0 — PROFILE PAGE JS (customer account settings)

   Reads / writes the signed-in user's own account through
   the API layer (§36 contract), so the page code is identical
   in mock mode and against Spring Security in Phase 3:

     GET  /api/users/me   → { user }   ← prefills every panel
     POST /api/users/me   → { user }   ← account details save

   Session ownership stays in auth.js: this page never touches
   the storage keys itself, it asks YatraAuth for the session
   and hands the server's answer back with updateCurrentUser()
   so the navbar chip and the next page agree with the server.

   Password changes are deliberately NOT persisted: hashing
   belongs to the backend's BCryptPasswordEncoder (Master Plan
   §3.4) and the mock store never holds a password. The form is
   a real UX flow (validation + strength meter) that will POST
   to /api/users/me/password in Phase 3 — nothing else changes.
   ========================================================= */
document.addEventListener('DOMContentLoaded', function () {
    'use strict';

    var $ = function (sel, root) { return (root || document).querySelector(sel); };
    var $id = function (id) { return document.getElementById(id); };

    /* Login/signup paint .field.has-error + <small class="error-msg">, which
       is exactly the markup this page uses — so reuse the shared rules
       instead of re-writing regexes here (spec §35). */
    if (typeof YatraValidation !== 'undefined') {
        YatraValidation.errorStyle = 'field';
    }

    var BOOKINGS_ROUTE = '/api/users/me/bookings';

    /* ---------- Guard (spec §35 auth.js; Master Plan §2.3 for the admin) ----------
       The profile page is the one customer screen that is meaningless without a
       session, so an expired/absent token goes back to the sign-in page. */
    if (typeof YatraAuth === 'undefined' || !YatraAuth.isLoggedIn()) {
        window.location.href = './login.html';
        return;
    }

    /* =========================================================
       Toast (same look as the admin panel's, styled in profile.css)
       ========================================================= */
    var toastTimer = null;
    function toast(message, type) {
        var el = $id('toast');
        if (!el) return;
        type = type || 'success';
        el.className = 'toast ' + type;
        el.innerHTML = '<i class="fa-solid ' +
            (type === 'error' ? 'fa-circle-exclamation' : 'fa-circle-check') + '"></i> ' + message;
        requestAnimationFrame(function () { el.classList.add('show'); });
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { el.classList.remove('show'); }, 3600);
    }

    /* =========================================================
       Formatting helpers
       ========================================================= */
    function initialOf(name) {
        return (String(name || 'T').trim().charAt(0) || 'T').toUpperCase();
    }

    function fmtDate(iso) {
        if (!iso) return '—';
        var d = new Date(String(iso).length > 10 ? iso : iso + 'T00:00:00');
        return isNaN(d) ? String(iso)
            : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
    }

    function fmtPhone(value) {
        var digits = (typeof MockDB !== 'undefined' && MockDB.normalizePhone)
            ? MockDB.normalizePhone(value)
            : String(value || '').replace(/\D/g, '');
        if (!digits) return '—';
        // 98XXXXXXXX — same grouping the admin panel shows.
        return digits.length === 10
            ? digits.slice(0, 2) + ' ' + digits.slice(2, 6) + ' ' + digits.slice(6)
            : digits;
    }

    function maskToken(token) {
        if (!token) return 'No token in this tab';
        var parts = String(token).split('.');
        return parts.length === 3
            ? 'JWT ••••' + String(token).slice(-6) + ' (' + parts.length + ' segments)'
            : '••••' + String(token).slice(-6);
    }

    /* =========================================================
       State
       ========================================================= */
    var user = null;   // the last { user } the API answered with
    var pristine = {}; // form values as loaded — powers Reset + dirty check

    /* =========================================================
       Render
       ========================================================= */
    function renderIdentity(u) {
        var name = u.name || 'Traveller';

        $id('pcAvatar').textContent = initialOf(name);
        $id('pcName').textContent = name;
        $id('pcEmail').textContent = u.email || 'No email on file';
        $id('pcRole').textContent = u.role || 'USER';
        $id('pcStatus').textContent = u.status || 'Active';
        $id('pcId').textContent = u.userId ? '#' + u.userId : '—';
        $id('pcPhone').textContent = fmtPhone(u.phone);
        $id('pcSince').textContent = fmtDate(u.registeredAt);

        var status = $id('pcStatus');
        status.classList.toggle('is-inactive', (u.status || 'Active') !== 'Active');

        // Navbar chip (shared markup, id-driven) + mobile menu name
        var avatar = $id('userAvatar');
        var chipName = $id('userName');
        if (avatar) avatar.textContent = initialOf(name);
        if (chipName) chipName.textContent = name;
        var mobileName = $('.mobile-user-name');
        if (mobileName) mobileName.innerHTML = '<i class="fa-solid fa-user"></i> ' + name;

        // Session panel
        $id('sessName').textContent = name + (u.email ? ' · ' + u.email : '');
        $id('sessToken').textContent = maskToken(YatraAuth.token());
        $id('sessRole').textContent = u.role || 'USER';
    }

    function prefillForm(u) {
        pristine = {
            name: u.name || '',
            email: u.email || '',
            phone: u.phone || ''
        };
        $id('pfName').value = pristine.name;
        $id('pfEmail').value = pristine.email;
        $id('pfPhone').value = pristine.phone;
    }

    /* Hero stats come from the same bookings store the admin panel reads, so
       "Total bookings" here and on my-bookings.html can never disagree. */
    function renderStats(u) {
        $id('statMemberSince').textContent = u.registeredAt
            ? new Date(u.registeredAt).toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
            : '—';

        apiGet(BOOKINGS_ROUTE).then(function (res) {
            var bookings = (res && res.bookings) || [];
            var today = YatraValidation.todayISO();
            var upcoming = bookings.filter(function (b) {
                var f = (b && b.flight) || {};
                return b.status !== 'Cancelled' && f.date && f.date >= today;
            }).length;
            $id('statBookings').textContent = bookings.length;
            $id('statUpcoming').textContent = upcoming;
        }).catch(function () {
            $id('statBookings').textContent = '0';
            $id('statUpcoming').textContent = '0';
        });
    }

    /* =========================================================
       Account details form
       ========================================================= */
    var detailsForm = $id('detailsForm');
    var saveBtn = $id('detailsSaveBtn');

    function clearErrors() {
        if (typeof YatraValidation !== 'undefined') YatraValidation.clearFormErrors(detailsForm);
    }

    function validateDetails() {
        var res = { valid: true, errors: [] };
        var name = $id('pfName');
        var email = $id('pfEmail');
        var phone = $id('pfPhone');
        var V = YatraValidation;

        function fail(field, message) {
            res.valid = false;
            res.errors.push(message);
            if (V && V.errorStyle === 'field') {
                var wrap = field.closest('.field');
                if (wrap) {
                    wrap.classList.add('has-error');
                    var msg = document.createElement('small');
                    msg.className = 'error-msg';
                    msg.textContent = message;
                    wrap.appendChild(msg);
                }
            }
            field.setAttribute('aria-invalid', 'true');
        }

        if (V.isBlank(name.value)) fail(name, 'Full name is required.');
        if (V.isBlank(email.value)) fail(email, 'Email address is required.');
        else if (!V.isEmail(email.value)) fail(email, 'Enter a valid email address (e.g. name@gmail.com).');
        if (V.isBlank(phone.value)) fail(phone, 'Mobile number is required.');
        else if (!V.isNepaliMobile(phone.value)) fail(phone, 'Enter a 10-digit mobile number starting with 9.');

        return res;
    }

    if (detailsForm) {
        // Clear a field's error as soon as the user edits it (same pattern as
        // login.html / signup.html).
        Array.prototype.forEach.call(detailsForm.querySelectorAll('input'), function (input) {
            input.addEventListener('input', function () {
                if (typeof YatraValidation !== 'undefined') YatraValidation.clearFieldError(input);
            });
        });

        detailsForm.addEventListener('submit', function (e) {
            e.preventDefault();
            clearErrors();

            var check = validateDetails();
            if (!check.valid) {
                toast(check.errors[0], 'error');
                return;
            }

            var payload = {
                name: $id('pfName').value.trim(),
                email: $id('pfEmail').value.trim(),
                phone: $id('pfPhone').value.trim()
            };

            var original = saveBtn.innerHTML;
            saveBtn.disabled = true;
            saveBtn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Saving…';

            apiPost('/api/users/me', payload).then(function (res) {
                user = res.user;
                // The server's answer is the truth — hand it back to the session
                // so the navbar chip and my-bookings scope agree immediately.
                YatraAuth.updateCurrentUser(user);
                renderIdentity(user);
                prefillForm(user);
                toast('Profile updated.');
            }).catch(function (err) {
                // 409 EMAIL_EXISTS / PHONE_EXISTS carry the backend's message.
                toast((err && err.message) || 'Could not save your profile.', 'error');
                if (err && err.code === 'EMAIL_EXISTS') {
                    var email = $id('pfEmail');
                    email.closest('.field').classList.add('has-error');
                } else if (err && err.code === 'PHONE_EXISTS') {
                    var phone = $id('pfPhone');
                    phone.closest('.field').classList.add('has-error');
                }
            }).finally(function () {
                saveBtn.disabled = false;
                saveBtn.innerHTML = original;
            });
        });
    }

    var resetBtn = $id('detailsResetBtn');
    if (resetBtn) {
        resetBtn.addEventListener('click', function () {
            clearErrors();
            $id('pfName').value = pristine.name;
            $id('pfEmail').value = pristine.email;
            $id('pfPhone').value = pristine.phone;
            toast('Form reset to the saved values.');
        });
    }

    /* =========================================================
       Password form (mock — the backend owns hashing)
       ========================================================= */
    var pwForm = $id('passwordForm');
    var pwNew = $id('pwNew');

    // Password visibility toggles (same [data-pw-toggle] convention as login.html)
    Array.prototype.forEach.call(document.querySelectorAll('[data-pw-toggle]'), function (btn) {
        btn.addEventListener('click', function () {
            var input = $id(btn.dataset.pwToggle);
            if (!input) return;
            var show = input.type === 'password';
            input.type = show ? 'text' : 'password';
            btn.innerHTML = '<i class="fa-regular ' + (show ? 'fa-eye-slash' : 'fa-eye') + '"></i>';
            btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
            input.focus({ preventScroll: true });
        });
    });

    /* Live strength meter — pure UX feedback, never a security boundary. */
    var LEVELS = [
        { label: 'Too short', color: 'var(--accent)', width: '18%' },
        { label: 'Weak', color: 'var(--accent)', width: '35%' },
        { label: 'Fair', color: '#d97706', width: '60%' },
        { label: 'Strong', color: 'var(--primary)', width: '82%' },
        { label: 'Very strong', color: 'var(--primary-dark)', width: '100%' }
    ];

    function scorePassword(value) {
        if (!value) return -1;
        var min = (typeof YatraValidation !== 'undefined') ? YatraValidation.PASSWORD_MIN : 8;
        if (value.length < min) return 0;
        var classes = 0;
        if (/[a-z]/.test(value)) classes++;
        if (/[A-Z]/.test(value)) classes++;
        if (/\d/.test(value)) classes++;
        if (/[^A-Za-z0-9]/.test(value)) classes++;
        return Math.min(4, classes); // 1 → Weak … 4 → Very strong
    }

    function renderMeter() {
        var meter = $id('pwMeter');
        if (!meter || !pwNew) return;
        var score = scorePassword(pwNew.value);

        if (score < 0) {
            meter.hidden = true;
            return;
        }
        meter.hidden = false;

        var level = LEVELS[score];
        var fill = $id('pwMeterFill');
        fill.style.width = level.width;
        fill.style.background = level.color;
        var text = $id('pwMeterText');
        text.textContent = level.label;
        text.style.color = level.color;
    }

    if (pwNew) {
        pwNew.addEventListener('input', function () {
            renderMeter();
            if (typeof YatraValidation !== 'undefined') YatraValidation.clearFieldError(pwNew);
        });
    }

    if (pwForm) {
        ['pwCurrent', 'pwConfirm'].forEach(function (id) {
            var input = $id(id);
            if (!input) return;
            input.addEventListener('input', function () {
                if (typeof YatraValidation !== 'undefined') YatraValidation.clearFieldError(input);
            });
        });

        pwForm.addEventListener('submit', function (e) {
            e.preventDefault();
            if (typeof YatraValidation !== 'undefined') YatraValidation.clearFormErrors(pwForm);

            var min = (typeof YatraValidation !== 'undefined') ? YatraValidation.PASSWORD_MIN : 8;
            var current = $id('pwCurrent');
            var confirm = $id('pwConfirm');
            var errors = [];

            function paint(field, message) {
                errors.push(message);
                var wrap = field.closest('.field');
                if (wrap) {
                    wrap.classList.add('has-error');
                    var msg = document.createElement('small');
                    msg.className = 'error-msg';
                    msg.textContent = message;
                    wrap.appendChild(msg);
                }
                field.setAttribute('aria-invalid', 'true');
            }

            if (!current.value) paint(current, 'Your current password is required.');
            if (!pwNew.value) paint(pwNew, 'Choose a new password.');
            else if (pwNew.value.length < min) paint(pwNew, 'New password must be at least ' + min + ' characters.');
            if (!confirm.value) paint(confirm, 'Please confirm the new password.');
            else if (pwNew.value && confirm.value !== pwNew.value) paint(confirm, 'Passwords do not match.');

            if (errors.length) {
                toast(errors[0], 'error');
                return;
            }

            var btn = $id('passwordSaveBtn');
            var original = btn.innerHTML;
            btn.disabled = true;
            btn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Updating…';

            /* Demo: the backend owns password storage, so nothing is persisted.
               The same form will POST /api/users/me/password in Phase 3 (the
               endpoint that runs BCryptPasswordEncoder) — only this block changes. */
            setTimeout(function () {
                pwForm.reset();
                $id('pwMeter').hidden = true;
                btn.disabled = false;
                btn.innerHTML = original;
                toast('Password updated (demo — the backend hashes it in Phase 3).');
            }, 800);
        });
    }

    /* =========================================================
       Sign out (shared with the navbar + mobile menu links)
       ========================================================= */
    function signOut() {
        YatraAuth.logout();
        window.location.href = './home.html';
    }

    ['profileLogout', 'sessionLogoutBtn'].forEach(function (id) {
        var btn = $id(id);
        if (btn) btn.addEventListener('click', signOut);
    });

    // The navbar .btn-logout + the mobile menu's Log Out row — auth.js owns
    // that binding and marks each link, so this is a no-op when the shared
    // navbar JS already ran on this page (and a fallback when it did not).
    // The anchors then navigate to home.html themselves.
    if (typeof YatraAuth.bindLogoutLinks === 'function') {
        YatraAuth.bindLogoutLinks();
    }

    /* =========================================================
       Load
       ========================================================= */
    apiGet('/api/users/me').then(function (res) {
        user = res.user;
        renderIdentity(user);
        prefillForm(user);
        renderStats(user);
    }).catch(function (err) {
        toast((err && err.message) || 'Could not load your profile.', 'error');
    });
});
