/* =========================================================
   YATRA 2.0 — THE FARE: one split, one rendering, one formatter

   fix-plan §5 asks the "Confirm Flight" modal to show "flight details
   + price breakdown (reuse the existing logic from booking.html's
   'View Price Breakdown', pulled into a shared function both pages
   call)". That reuse was impossible: the logic was private to
   booking.js, and the same split had already been written a second
   time in payment.js — whose own comment says "the same deterministic
   split booking.js shows", which is a comment standing in for a
   shared function. A third copy sat in payment.html as hardcoded
   rows (removed with this change).

   WHY THE SPLIT NEEDS TO BE AUTHORITATIVE AND NOT CENTRAL-ISH.
   The three rows have to add up to the total the user is charged.
   `service` is defined as the remainder precisely so they do, and
   that only holds if every page derives all three from one place.
   booking.js did not: its "Base Fare" row printed the TOTAL rather
   than `base`, so on that page the breakdown read
   8,299.99 + 581.00 + 415.00 against a stated total of 8,299.99 —
   it visibly did not add up. payment.js printed `base` correctly, so
   the same numbers disagreed between the two steps of one booking.
   The extraction fixes that by construction: there is now one place
   that decides what each row says.

   The rates are the original ones from booking.js (88% base, 7% tax,
   remainder service) — unchanged, so no price moves. Only the value
   the "Base Fare" row reports was corrected, and only because it was
   the inconsistent one.
   ========================================================= */
var YatraFare = (function () {
    'use strict';

    /** Base fare as a share of the total. */
    var BASE_RATE = 0.88;
    /** Estimated airport tax as a share of the total. */
    var TAX_RATE = 0.07;

    /** Two decimal places, as a number — the half-up rounding the pages used. */
    function round2(n) {
        return Math.round(n * 100) / 100;
    }

    /**
     * {@code "NPR 8,299.99"} — the one money format in the flow.
     *
     * <p>Guards a missing value to 0 rather than printing "NPR NaN"; the
     * booking page's own copy did not, and payment.js's did, which is the kind
     * of difference two implementations produce and one cannot.
     */
    function format(n) {
        return 'NPR ' + Number(n || 0).toLocaleString('en-US', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2
        });
    }

    /**
     * Splits a total into the three rows the fare breakdown shows.
     *
     * <p>{@code service} is the remainder, so `base + tax + service === total`
     * exactly — which is the property the breakdown exists to demonstrate.
     *
     * @param {number} total the amount charged for the whole booking
     * @returns {{base: number, tax: number, service: number, total: number}}
     */
    function split(total) {
        var t = round2(Number(total) || 0);
        var base = round2(t * BASE_RATE);
        var tax = round2(t * TAX_RATE);
        return { base: base, tax: tax, service: round2(t - base - tax), total: t };
    }

    /**
     * The breakdown as data, so a caller can render or test it without parsing
     * HTML — §5's modal needs the numbers, not this page's markup.
     *
     * @param {number} total      the amount charged for the whole booking
     * @param {{passengers?: number, unitPrice?: number}} [opts] the per-passenger
     *        price and count, only for the "Base Fare (2 × NPR …)" label; when
     *        either is missing the label is left plain rather than half-filled
     * @returns {Array<{label: string, value: string, total: boolean}>}
     */
    function rows(total, opts) {
        opts = opts || {};
        var parts = split(total);
        var pax = Number(opts.passengers || 0);
        var unit = Number(opts.unitPrice);

        var label = 'Base Fare';
        if (pax > 0 && unit > 0) {
            label += ' (' + pax + ' \u00d7 ' + format(unit) + ')';
        }

        return [
            { label: label, value: format(parts.base), total: false },
            { label: 'Airport Tax (est.)', value: format(parts.tax), total: false },
            { label: 'Service Fee', value: format(parts.service), total: false },
            { label: 'Total', value: format(parts.total), total: true }
        ];
    }

    /** {@link rows} as the `.breakdown-row` markup both pages use. */
    function html(total, opts) {
        return rows(total, opts).map(function (r) {
            return '<div class="breakdown-row' + (r.total ? ' total' : '') + '">' +
                '<span>' + r.label + '</span><span>' + r.value + '</span></div>';
        }).join('');
    }

    /** Paints the breakdown into an element, if the page has one. */
    function render(el, total, opts) {
        if (!el) return null;
        var parts = split(total);
        el.innerHTML = html(total, opts);
        return parts;
    }

    return {
        BASE_RATE: BASE_RATE,
        TAX_RATE: TAX_RATE,
        round2: round2,
        format: format,
        split: split,
        rows: rows,
        html: html,
        render: render
    };
})();
