/* =========================================================
   YATRA 2.0 — §3's DEPARTURE/ARRIVAL CITY RULES

   Extracted from homeLogged.js, where these rules were unreachable
   from any other page. fix-plan §4 asks searchFlight.html's "Modify
   Flight" popup to reuse "the same search form + validation rules
   from §3" — and it could not, because the rules lived inside the
   one file that page deliberately does not load (searchFlight.html's
   own comment: "This page's navbar is wired by searchFlight.js, not
   homeLogged.js" — the Session 33 decision that the wizard pages
   avoid the shared file so #mobileMenuBtn is not double-bound).

   So the rules moved here rather than being copied. A copy would have
   been the second implementation of the same two rules, and §3's rules
   are exactly the kind that drift apart silently: rule 1 is enforced
   by REMOVING OPTIONS, so a page that forgets it still works — it
   just quietly allows a departure and arrival in the same city.

   The two rules (§3, verbatim):
     1. a city cannot be chosen on both sides;
     2. arrival stays closed until departure has a value.

   Neither rule invents a city: the option list is the markup's own,
   and the only thing ever done to it is removing the option the other
   field already holds. Nothing here reads the API, the session, or
   any other module — it is pure DOM wiring, so it works on a page
   whose only source of cities is the markup.
   ========================================================= */
var YatraCityPair = (function () {
    'use strict';

    /** Every pair wired so far, by origin id — see reset() and the setup() guard. */
    var pairs = {};

    /**
     * Wires one departure/arrival pair.
     *
     * @param {string} swapBtnId id of the swap button (may be absent — the button
     *        is optional, the rules are not)
     * @param {string} originId  id of the departure <select>
     * @param {string} destId    id of the arrival <select>
     * @returns {boolean} whether the pair was wired
     */
    function setup(swapBtnId, originId, destId) {
        var origin = document.getElementById(originId);
        var dest = document.getElementById(destId);
        if (!origin || !dest) return false;

        /* Wired at most once per pair, because the snapshot below reads the CURRENT
           option list — and rule 1 removes options from it. Setting the same pair up
           a second time would snapshot a list rule 1 had already filtered, so the
           city list would quietly shrink every time a caller re-ran setup. A guard
           is the only way a caller cannot make that mistake. */
        if (pairs[originId]) return false;

        /* Snapshot the markup's options once — they are the source of truth. */
        var all = Array.from(origin.options).map(function (o) {
            return { value: o.value, label: o.textContent };
        });
        var placeholder = all.find(function (o) { return o.value === ''; })
            || { value: '', label: 'Select a city' };
        var cities = all.filter(function (o) { return o.value !== ''; });

        function rebuild(select, exclude) {
            var keep = select.value;
            select.innerHTML = '';
            select.appendChild(new Option(placeholder.label, ''));
            cities.forEach(function (city) {
                if (city.value !== exclude) select.appendChild(new Option(city.label, city.value));
            });
            /* Keep the current choice when it survived the filter, so re-rendering
               never clears a field the user did not touch. */
            select.value = (keep && keep !== exclude) ? keep : '';
        }

        function sync() {
            var chosen = origin.value;
            dest.disabled = !chosen;          // rule 2
            rebuild(dest, chosen);            // rule 1, one direction
            rebuild(origin, dest.value);      // rule 1, the other
            if (!chosen) dest.value = '';     // no departure, so no arrival
        }

        /* The swap does its own assigning rather than reusing a plain value
           exchange, because rule 1 has already REMOVED the value being swapped in
           from the list it is being assigned to. Setting a <select> to a value it
           no longer offers silently yields '' — both fields would blank, and sync
           would then read an empty departure and close the arrival. Holding both
           values, restoring the unfiltered lists, then letting sync re-apply the
           rules is the only order that survives.

           Nothing is lost here: rule 2 leaves the arrival empty and disabled
           whenever the departure is empty, so a swap only ever runs with both
           sides set or neither. */
        var swapBtn = document.getElementById(swapBtnId);
        if (swapBtn) {
            swapBtn.addEventListener('click', function () {
                var from = origin.value;
                var to = dest.value;
                rebuild(origin, '');
                rebuild(dest, '');
                origin.value = to;
                dest.value = from;
                swapBtn.style.transform = 'rotate(180deg)';
                setTimeout(function () { swapBtn.style.transform = 'rotate(0deg)'; }, 300);
                sync();
            });
        }

        origin.addEventListener('change', sync);
        dest.addEventListener('change', sync);
        sync();

        pairs[originId] = { origin: origin, dest: dest, rebuild: rebuild };
        return true;
    }

    /**
     * Restores a pair's option lists to the markup's own, unfiltered ones.
     *
     * <p>For a caller that has to SET a value rule 1 may have removed. fix-plan §4's
     * "Modify Flight" popup opens prefilled with the route currently on screen, and
     * the city it wants to put in the arrival select may have been filtered out of
     * that select by the departure's own value. Assigning to a {@code <select>} that
     * no longer offers the value silently yields {@code ''} — the same trap the swap
     * button documents — so the lists are restored first.
     *
     * <p>Lists only, deliberately: the rules are re-applied by the next {@code change}
     * event, so a caller sets the departure (letting it sync) and then the arrival.
     *
     * @param {string} originId the pair's departure id, as passed to setup()
     * @returns {boolean} whether that pair was wired
     */
    function reset(originId) {
        var pair = pairs[originId];
        if (!pair) return false;
        pair.rebuild(pair.origin, '');
        pair.rebuild(pair.dest, '');
        return true;
    }

    /**
     * Wires every pair on the page from a list of id triples, so a page that adds
     * a second search form (searchFlight.html's §4 popup) names it once here
     * instead of remembering to call setup() again.
     *
     * @param {Array<Array<string>>} pairs triples of [swapBtnId, originId, destId]
     */
    function setupAll(pairs) {
        var wired = 0;
        (pairs || []).forEach(function (t) {
            if (setup(t[0], t[1], t[2])) wired++;
        });
        return wired;
    }

    return {
        setup: setup,
        setupAll: setupAll,
        reset: reset
    };
})();
