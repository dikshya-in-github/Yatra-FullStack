/* =========================================
   YATRA ADMIN — PROFILE PAGE JS

   The admin's own account + logout. Reads and writes the real
   endpoint, named in config.js's REAL_API_PAGES:

     GET  /api/admin/profile  → { user }
     POST /api/admin/profile  → { user }

   AdminProfileController resolves the caller from the JWT's
   `sub` claim, so there is no id in the URL to tamper with and
   the page posts the same empty-path body it always did. It
   answers 401 when no admin session exists, which is why the
   load path can bounce to the sign-in page. admin.js has
   already run its guard by the time this file executes (it is
   loaded first, so its DOMContentLoaded listener is registered
   first).

   The account row is the SAME record the Users page manages —
   one roster for signup, admin-users and this page, so an edit
   here can never drift from the panel.

   Passwords are never stored or compared client-side. The
   Change Password form is NOT wired: the API serves no
   password-change route, so it validates the real rules and
   then says so in the open (see the submit handler). It used to
   reset the fields and toast "Password updated" from a
   setTimeout — a success message for a change that never
   happened. The rules above it are unchanged, so wiring it the
   day the endpoint lands is a one-block edit.
   ========================================= */
document.addEventListener('DOMContentLoaded', function () {
    'use strict';

    var $ = function (sel, root) { return (root || document).querySelector(sel); };
    var $id = function (id) { return document.getElementById(id); };

    var cfg = (typeof YATRA_CONFIG !== 'undefined') ? YATRA_CONFIG : {};
    var ADMIN_SESSION_KEY = cfg.ADMIN_SESSION_KEY || 'yatra_admin_session';

    /* admin.css ships .a-field.has-error + .error-msg, so the shared painter
       is the right one here (same markup convention as the customer forms). */
    if (typeof YatraValidation !== 'undefined') {
        YatraValidation.errorStyle = 'field';
    }

    /* ---------- Session ---------- */
    function adminSession() {
        try {
            return JSON.parse(sessionStorage.getItem(ADMIN_SESSION_KEY) || 'null');
        } catch (err) {
            return null;
        }
    }

    function saveAdminSession(user) {
        var current = adminSession() || {};
        var next = {
            userId: user.userId != null ? user.userId : current.userId,
            name: user.name || current.name,
            email: user.email || current.email,
            role: user.role || current.role || 'ADMIN'
        };
        sessionStorage.setItem(ADMIN_SESSION_KEY, JSON.stringify(next));
        return next;
    }

    /* ---------- Toast ---------- */
    /* showToast() lives in toast.js (§9) — one implementation for every page,
       which also creates the #toast element it writes to. */

    /* ---------- Formatting ---------- */
    function initialOf(name) {
        return (String(name || 'A').trim().charAt(0) || 'A').toUpperCase();
    }

    function fmtDate(iso) {
        if (!iso) return '—';
        var d = new Date(String(iso).length > 10 ? iso : iso + 'T00:00:00');
        return isNaN(d) ? String(iso)
            : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
    }

    /* Only the last six characters, and no vocabulary about how the key is built:
       the old version printed "JWT ••••xxxxxx (3 segments)", which describes the
       implementation to whoever is looking at the screen. */
    function maskToken(token) {
        if (!token) return 'No session key in this tab';
        return '••••' + String(token).slice(-6);
    }

    /* ---------- State ---------- */
    var user = null;
    var pristine = {};

    /* ---------- Render ---------- */
    function render(u) {
        var name = u.name || 'Admin';

        $id('profileAvatar').textContent = initialOf(name);
        $id('profileName').textContent = name;
        $id('profileEmail').textContent = u.email || 'No email on file';
        $id('profileRole').textContent = u.role || 'ADMIN';
        $id('profileStatus').textContent = u.status || 'Active';
        $id('profileMember').textContent = u.registeredAt
            ? 'Member since ' + fmtDate(u.registeredAt)
            : 'Member since —';

        // The sidebar/topbar identity admin.js filled from the session must
        // agree with what the API just told us.
        var avatar = initialOf(name);
        $id('sidebarAvatar').textContent = avatar;
        $id('adminAvatar').textContent = avatar;
        $id('adminName').textContent = name;
        $id('sidebarName').textContent = name;
        if (u.email) $id('sidebarEmail').textContent = u.email;

        // Session block
        $id('sessName').textContent = name + (u.email ? ' · ' + u.email : '');
        $id('sessRole').textContent = u.role || 'ADMIN';
        $id('sessId').textContent = u.userId ? '#' + u.userId : '—';
        $id('sessToken').textContent = maskToken((function () {
            try { return sessionStorage.getItem(cfg.AUTH_TOKEN_KEY || 'yatra_auth_token'); } catch (e) { return null; }
        })());
    }

    function prefill(u) {
        pristine = { name: u.name || '', email: u.email || '', phone: u.phone || '' };
        $id('pfName').value = pristine.name;
        $id('pfEmail').value = pristine.email;
        $id('pfPhone').value = pristine.phone;
    }

    /* ---------- Account details ---------- */
    var form = $id('profileForm');
    var saveBtn = $id('profileSaveBtn');

    function clearErrors() {
        if (typeof YatraValidation !== 'undefined') YatraValidation.clearFormErrors(form);
    }

    if (form) {
        Array.prototype.forEach.call(form.querySelectorAll('input'), function (input) {
            input.addEventListener('input', function () {
                if (typeof YatraValidation !== 'undefined') YatraValidation.clearFieldError(input);
            });
        });

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            clearErrors();

            var V = YatraValidation;
            var nameEl = $id('pfName');
            var emailEl = $id('pfEmail');
            var phoneEl = $id('pfPhone');
            var errors = [];

            function paint(field, message) {
                errors.push(message);
                var wrap = field.closest('.a-field');
                if (wrap) {
                    wrap.classList.add('has-error');
                    var msg = document.createElement('small');
                    msg.className = 'error-msg';
                    msg.textContent = message;
                    wrap.appendChild(msg);
                }
                field.setAttribute('aria-invalid', 'true');
            }

            if (V.isBlank(nameEl.value)) paint(nameEl, 'Full name is required.');
            if (V.isBlank(emailEl.value)) paint(emailEl, 'Email address is required.');
            else if (!V.isEmail(emailEl.value)) paint(emailEl, 'Enter a valid email address (e.g. name@example.com).');
            if (V.isBlank(phoneEl.value)) paint(phoneEl, 'Mobile number is required.');
            else if (!V.isNepaliMobile(phoneEl.value)) paint(phoneEl, 'Enter a 10-digit mobile number starting with 9.');

            if (errors.length) {
                showToast(errors[0], 'error');
                return;
            }

            var original = saveBtn.innerHTML;
            saveBtn.disabled = true;
            saveBtn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Saving…';

            apiPost('/api/admin/profile', {
                name: nameEl.value.trim(),
                email: emailEl.value.trim(),
                phone: phoneEl.value.trim()
            }).then(function (res) {
                user = res.user;
                saveAdminSession(user);   // the guard + sidebar read this key
                render(user);
                prefill(user);
                showToast('Profile updated.');
            }).catch(function (err) {
                showToast((err && err.message) || 'Could not save your profile.', 'error');
                if (err && err.code === 'EMAIL_EXISTS') {
                    $id('pfEmail').closest('.a-field').classList.add('has-error');
                } else if (err && err.code === 'PHONE_EXISTS') {
                    $id('pfPhone').closest('.a-field').classList.add('has-error');
                }
            }).finally(function () {
                saveBtn.disabled = false;
                saveBtn.innerHTML = original;
            });
        });
    }

    var resetBtn = $id('profileResetBtn');
    if (resetBtn) {
        resetBtn.addEventListener('click', function () {
            clearErrors();
            $id('pfName').value = pristine.name;
            $id('pfEmail').value = pristine.email;
            $id('pfPhone').value = pristine.phone;
            showToast('Form reset to the saved values.');
        });
    }

    /* ---------- Password (mock — the backend hashes it) ---------- */
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
        return Math.min(4, classes);
    }

    var pwNew = $id('pwNew');
    function renderMeter() {
        var meter = $id('pwMeter');
        if (!meter) return;
        var score = scorePassword(pwNew.value);
        if (score < 0) { meter.hidden = true; return; }
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

    var pwForm = $id('passwordForm');
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

            var V = YatraValidation;
            var min = (V && V.PASSWORD_MIN) || 8;
            var current = $id('pwCurrent');
            var confirm = $id('pwConfirm');
            var errors = [];

            function paint(field, message) {
                errors.push(message);
                var wrap = field.closest('.a-field');
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
                showToast(errors[0], 'error');
                return;
            }

            /* Nothing is sent and nothing is claimed. There is no password-change
               route to call (AdminProfileController's own note records the decision),
               so the form refuses in the OPEN rather than confirming a change it did
               not make — which is what this block used to do: reset the fields, hide
               the meter and toast "Password updated" from an 800ms setTimeout.
               The fields deliberately keep their values: clearing them is the visual
               language of a successful save. */
            $id('pwUnavailable').hidden = false;
            showToast('Nothing was saved — changing your password is not available in this build.',
                'error');
        });
    }

    /* ---------- Sign out ----------
       admin.js already bound #adminLogout (it clears the session and returns
       to admin-login.html). These two extra buttons do the same thing so the
       action is reachable from the page body, not only the sidebar. */
    function signOut() {
        sessionStorage.removeItem(ADMIN_SESSION_KEY);
        window.location.href = './admin-login.html';
    }

    ['profileSignOut', 'sessionSignOut'].forEach(function (id) {
        var btn = $id(id);
        if (btn) btn.addEventListener('click', signOut);
    });

    /* ---------- Load ---------- */
    apiGet('/api/admin/profile').then(function (res) {
        user = res.user;
        render(user);
        prefill(user);
    }).catch(function (err) {
        showToast((err && err.message) || 'Could not load your profile.', 'error');
        // 401 means the session is gone (expired tab) — admin.js's guard cannot
        // catch that case because it runs once at load.
        if (err && err.status === 401) {
            setTimeout(signOut, 900);
        }
    });
});
