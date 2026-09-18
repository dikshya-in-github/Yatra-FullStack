/* =========================================================
   YATRA 2.0 — THE BOOKING HOLD'S CLOCK (fix-plan §8a)

   One implementation, because two pages need the same 15-minute
   hold and two copies of a countdown is exactly how the two pages
   would come to disagree about it.

   WHY THIS EXISTS AT ALL. The countdown used to be a counter:

       let timeLeft = parseInt(sessionStorage.getItem('bookingTimeLeft'))
                      || TIMER_DURATION;
       setInterval(() => { timeLeft--; ... }, 1000);

   A background tab throttles `setInterval`, so the counter fell
   behind real time every time the user switched tabs, and a
   back/forward navigation re-ran the page while the stored value
   kept its old meaning. The remaining time is now always derived
   from a fixed point:

       remainingMs() === expiry - Date.now()

   which is correct regardless of tab focus, throttling, or
   navigation, because nothing is being accumulated.

   THE KEY. `yatra_hold_expiry` is an absolute epoch-millisecond
   timestamp and replaces `bookingTimeLeft`. It is deliberately a
   new name rather than the old one reused: the old key held a
   *duration* and the new one holds an *instant*, and a page that
   read one as the other would show a countdown of about 55 years
   (or expire instantly) with no error anywhere. Renaming forces
   every reader to be revisited, and there is only one reader left
   (see `holdExpiryKey`'s removal note in requirements.md).
   ========================================================= */
var YatraHold = (function () {
    'use strict';

    /** The sessionStorage key: an absolute epoch-ms timestamp, not a duration. */
    var KEY = 'yatra_hold_expiry';

    /** §8 gives the hold 15 minutes. */
    var DURATION_MS = 15 * 60 * 1000;

    /** The stored expiry, or null when no hold has been started. */
    function expiry() {
        var raw = parseInt(sessionStorage.getItem(KEY), 10);
        return isNaN(raw) ? null : raw;
    }

    /**
     * Starts a hold if the session does not already have one, and returns the
     * expiry either way.
     *
     * <p>Idempotent on purpose. Both pages call it, and the second call must
     * resume the first one's hold rather than restart the clock — otherwise
     * moving from booking.html to payment.html would hand the user a fresh 15
     * minutes each step and the hold would never actually expire.
     *
     * <p>An <i>expired</i> stored value is left in place, not replaced. Arriving
     * on a page after the hold has run out is a real state the page has to
     * handle (it expires immediately and bounces), and silently restarting the
     * clock here would hide it.
     */
    function start() {
        if (expiry() === null) {
            sessionStorage.setItem(KEY, String(Date.now() + DURATION_MS));
        }
        return expiry();
    }

    /** Milliseconds left. Never negative. */
    function remainingMs() {
        return Math.max(0, remainingMsRaw());
    }

    /** Milliseconds left, which may be negative — useful to tell "expired" from "at zero". */
    function remainingMsRaw() {
        var at = expiry();
        return at === null ? DURATION_MS : at - Date.now();
    }

    /** The hold is over. Called when the flow completes or is abandoned. */
    function clear() {
        sessionStorage.removeItem(KEY);
    }

    /**
     * Every key an abandoned booking attempt leaves behind (fix-plan §8b's "clears
     * the relevant sessionStorage keys").
     *
     * <p>The hold itself, the chosen flight, the contact/passenger payload (and the
     * pending booking id inside it), the e-ticket handoff, an in-flight payment, the
     * search that produced the attempt, and the §6 marker that sends a signed-out
     * user back to booking.html after they sign in. Leaving any of them would let the
     * next attempt start from a half-populated state the user never entered — and the
     * marker especially: left behind, the next sign-in would resume a booking whose
     * flight is no longer in session.
     *
     * <p><b>Deliberately NOT here:</b> {@code yatra_auth_token} / {@code yatra_auth_user} —
     * an expired hold is not a sign-out, and §8b sends the user to `homeLogged.html`
     * precisely because they are still signed in — and {@code yatra_transaction},
     * which is the record of a <i>completed</i> payment (re-written on demand by
     * `myBookings.js` when a ticket is viewed), not part of an abandoned attempt.
     */
    var FLOW_KEYS = [KEY, 'yatra_selected_flight', 'bookingData', 'yatra_passenger',
                     'yatra_pending_payment', 'flightSearchData', 'yatra_resume_booking'];

    /** Removes the abandoned attempt's keys. DOM-free on purpose, so it is unit-testable. */
    function clearFlow() {
        for (var i = 0; i < FLOW_KEYS.length; i++) {
            sessionStorage.removeItem(FLOW_KEYS[i]);
        }
    }

    /** Is there a session to return to? (auth.js owns the token; never read here.) */
    function loggedIn() {
        return typeof YatraAuth !== 'undefined'
            && typeof YatraAuth.isLoggedIn === 'function'
            && YatraAuth.isLoggedIn();
    }

    /**
     * The expiry screen fix-plan §8b asks for.
     *
     * <p>Built here rather than written into `booking.html` and `payment.html`
     * separately: it is the same screen with the same copy and the same two keys to
     * clear, and two copies of it is exactly the drift this module exists to prevent
     * (the clock was one copy; this is the other half of one flow). It is offered as a
     * ready-made handler — `watch({ onExpire: YatraHold.expireScreen })` — because
     * every page in the flow wants precisely this. `watch()` is unchanged in
     * principle: a page that wants a different consequence still passes its own.
     *
     * <p>The markup uses `.modal-backdrop` / `.modal-card` / `.modal-header` /
     * `.modal-body` / `.modal-footer`, all already defined in the shared
     * `homeLogged.css` that both wizard pages load, so this adds no CSS. Use
     * `.modal-header`/`.close-modal`, NOT the `searchFlight.html` spellings
     * (`.modal-head`/`.modal-close`), which live in `searchFlight.css` and would be
     * unstyled on these pages.
     */
    function expireScreen() {
        clearFlow();
        if (document.getElementById('holdExpiredModal')) return;

        var backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.id = 'holdExpiredModal';
        backdrop.setAttribute('aria-hidden', 'true');

        var card = document.createElement('div');
        card.className = 'modal-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');
        card.setAttribute('aria-labelledby', 'holdExpiredTitle');

        var header = document.createElement('div');
        header.className = 'modal-header';
        header.innerHTML = '<h3 id="holdExpiredTitle">Your Session Has Expired</h3>';

        var body = document.createElement('div');
        body.className = 'modal-body';
        body.innerHTML = '<p>Unfortunately, you have not taken any action for more than '
            + '15 minutes and your session has timed out.</p>';

        var footer = document.createElement('div');
        footer.className = 'modal-footer';
        var restart = document.createElement('button');
        restart.type = 'button';
        restart.className = 'btn btn-primary';
        restart.id = 'holdExpiredRestart';
        restart.textContent = 'Start a new search';
        restart.addEventListener('click', function () {
            /* §8b: homeLogged.html when signed in, home.html when not. The session is
               left intact either way — the hold expired, the login did not. */
            window.location.href = loggedIn() ? './homeLogged.html' : './home.html';
        });
        footer.appendChild(restart);

        card.appendChild(header);
        card.appendChild(body);
        card.appendChild(footer);
        backdrop.appendChild(card);
        document.body.appendChild(backdrop);

        /* `.open` is added synchronously rather than inside requestAnimationFrame.
           That class carries the modal's STATE, and a state that only exists once a
           frame has been painted is a state a background tab never reaches — rAF does
           not fire while the tab is hidden, which is precisely the situation a hold
           expires in. `void offsetHeight` gives the opacity/scale transition its start
           state without deferring the class; it is the same "void offsetWidth" trick
           ticketStatus.js uses to restart an animation, and careers.js also adds
           `.open` directly to its modal. */
        void backdrop.offsetHeight;
        backdrop.classList.add('open');
        backdrop.setAttribute('aria-hidden', 'false');
    }

    /** {@code "13 minutes 20 seconds"} — the wording the two pages already showed. */
    function format(ms) {
        var total = Math.max(0, Math.ceil(ms / 1000));
        var m = Math.floor(total / 60);
        var s = total % 60;
        return m + ' minute' + (m !== 1 ? 's' : '') + ' '
            + s + ' second' + (s !== 1 ? 's' : '');
    }

    /**
     * Paints the countdown and calls {@code onExpire} once when it runs out.
     *
     * <p>The helper owns the <b>time</b> — the part that was wrong, and the part
     * that must behave identically on both pages. The caller owns the
     * <b>consequence</b>, because what to do about an expired hold (which keys
     * to drop, where to send the user, and §8b's planned modal) is the page's
     * decision, not the clock's.
     *
     * <p>The warning thresholds are the ones the pages already used: `warning`
     * at five minutes, `danger` at two.
     *
     * @param {{display: Element|null, bar: Element|null, onExpire: Function}} opts
     * @returns {Function} stop — clears the interval (used by nothing yet; kept so
     *          a page that leaves the flow early does not leave a timer running)
     */
    function watch(opts) {
        opts = opts || {};
        var display = opts.display || null;
        var bar = opts.bar || null;
        var expired = false;

        function paint() {
            var ms = remainingMs();
            if (display) display.textContent = format(ms);
            if (bar) {
                var seconds = ms / 1000;
                bar.classList.remove('warning', 'danger');
                if (seconds <= 120) bar.classList.add('danger');
                else if (seconds <= 300) bar.classList.add('warning');
            }
            return ms;
        }

        function tick() {
            if (remainingMsRaw() <= 0 && !expired) {
                expired = true;
                clearInterval(handle);
                paint();
                if (typeof opts.onExpire === 'function') opts.onExpire();
                return;
            }
            paint();
        }

        /* §8b: an already-expired hold has to show the expiry screen ON LOAD, not one
           tick later, so the first check is immediate rather than waiting a second.
           When it fires there is nothing left to count, so the interval is never
           started — the screen is the end of the flow, not something that ticks. */
        var handle = null;
        paint();
        tick();
        if (!expired) handle = setInterval(tick, 1000);
        return function stop() { clearInterval(handle); };
    }

    return {
        KEY: KEY,
        DURATION_MS: DURATION_MS,
        FLOW_KEYS: FLOW_KEYS,
        expiry: expiry,
        start: start,
        remainingMs: remainingMs,
        clear: clear,
        clearFlow: clearFlow,
        expireScreen: expireScreen,
        format: format,
        watch: watch
    };
})();
