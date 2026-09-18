/* =========================================================
   toast.js — the one toast (§9)

   Twelve copies of this used to exist: nine in page scripts (six admin
   modules, admin-profile, profile, and the eSewa OTP helper) and three
   written inline in login.html, signup.html and admin-login.html. They
   had drifted — five dismissal timings (2600 / 3200 / 3400 / 3600 /
   4000), two signatures, one copy with no clearTimeout at all (so a
   second toast was hidden early by the first one's stale timer), and
   two that read '#toast' before checking it existed.

   Now one implementation, and it owns its own element: a page either
   loads this file or it has no toast. The class names are unchanged
   ('toast', plus the type, plus 'show') so each page's existing .toast
   CSS styles it exactly as before.
   ========================================================= */
var YatraToast = (function () {
    'use strict';

    /* One duration for every page. 3200ms was the majority spelling —
       the six admin modules and admin-login.html — so it is the one that
       survives; login/signup used 4000, profile 3600, admin-profile
       3400 and the eSewa OTP helper 2600. */
    var DURATION_MS = 3200;

    /* The two types every copy already supported. */
    var ICONS = { success: 'fa-circle-check', error: 'fa-circle-exclamation' };

    /* One timer for the page, so overlapping toasts cannot cut each
       other short. */
    var timer = null;

    /* Created on first use — this is the markup the eleven templates used
       to paste in, with the same id, classes, role and aria-live. */
    function container() {
        var el = document.getElementById('toast');
        if (!el) {
            el = document.createElement('div');
            el.id = 'toast';
            el.className = 'toast';
            el.setAttribute('role', 'status');
            el.setAttribute('aria-live', 'polite');
            (document.body || document.documentElement).appendChild(el);
        }
        return el;
    }

    function show(message, type) {
        if (!ICONS[type]) type = 'success';
        var el = container();
        el.className = 'toast ' + type;

        /* Built as nodes rather than innerHTML: these messages include API
           error strings, and the twelve copies interpolated them raw. */
        el.textContent = '';
        var icon = document.createElement('i');
        icon.className = 'fa-solid ' + ICONS[type];
        el.appendChild(icon);
        el.appendChild(document.createTextNode(' ' + message));

        /* 'show' is added synchronously, after a forced reflow rather than
           inside requestAnimationFrame: rAF does not fire while the tab is
           backgrounded — exactly when an async response lands — which would
           leave the toast at opacity 0 and lose the message entirely. Same
           reasoning as hold.js's expiry screen; ticketStatus.js uses the
           same reflow trick. */
        void el.offsetHeight;
        el.classList.add('show');

        clearTimeout(timer);
        timer = setTimeout(function () { el.classList.remove('show'); }, DURATION_MS);
        return el;
    }

    return { show: show, DURATION_MS: DURATION_MS };
})();

/* §9's name, and what every call site in the app says. */
function showToast(message, type) {
    return YatraToast.show(message, type);
}
