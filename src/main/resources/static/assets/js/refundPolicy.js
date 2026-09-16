document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. FAQ ACCORDION
    // ==========================================
    document.querySelectorAll('.faq-item').forEach(item => {
        const q = item.querySelector('.faq-q');
        const a = item.querySelector('.faq-a');
        if (!q || !a) return;
        q.addEventListener('click', () => {
            const isOpen = item.classList.contains('open');
            document.querySelectorAll('.faq-item.open').forEach(other => {
                other.classList.remove('open');
                const otherA = other.querySelector('.faq-a');
                if (otherA) otherA.style.maxHeight = null;
            });
            if (!isOpen) {
                item.classList.add('open');
                a.style.maxHeight = a.scrollHeight + 'px';
            }
        });
    });

    // ==========================================
    // 2. CANCELLATION FEE ESTIMATOR
    // Fee rules (Buddha Air domestic fare rules, Jan 2026)
    // ==========================================
    const RULES = {
        flexi: { label: 'Flexi (Y)', fees: { long: 0.10, short: 0.3333 } },
        standard: { label: 'Standard (A)', fees: { long: 0.25, short: 0.50 } },
        saver: { label: 'Saver (B)', fees: { long: null, short: null } },   // fare forfeited
        promo: { label: 'Promo (C/D/E)', fees: { long: null, short: null } } // non-refundable
    };

    const estBtn = document.getElementById('estBtn');
    const estResult = document.getElementById('estResult');
    const fmt = (n) => 'NPR ' + n.toLocaleString('en-IN');

    function estimate() {
        if (!estResult) return;
        const fare = parseFloat(document.getElementById('estFare')?.value);
        const cls = document.getElementById('estClass')?.value;
        const hours = parseFloat(document.getElementById('estHours')?.value);

        if (isNaN(fare) || fare <= 0 || isNaN(hours) || hours < 0) {
            estResult.className = 'estimate-result';
            estResult.innerHTML = 'Please enter a valid fare and hours before departure.';
            return;
        }

        const rule = RULES[cls];

        if (hours < 2) {
            estResult.className = 'estimate-result bad';
            estResult.innerHTML =
                '<strong>Cancellation not permitted</strong> — it\'s within 2 hours of departure. ' +
                "If you don't board, the ticket becomes a no-show (fare forfeited; airport tax claimable). " +
                'Exceptions: airline cancellation or a delay over 1 hour = full refund.';
            return;
        }

        const feePct = hours > 11 ? rule.fees.long : rule.fees.short;

        if (feePct === null) {
            estResult.className = 'estimate-result bad';
            estResult.innerHTML =
                '<strong>' + rule.label + ':</strong> the base fare (' + fmt(fare) + ') is forfeited on cancellation. ' +
                'Only the unutilized fuel surcharge' + (cls === 'promo' ? '/Passenger Service Charge' : ' and Passenger Service Charge') +
                ' are refundable on request.';
            return;
        }

        const fee = Math.round(fare * feePct);
        const refund = fare - fee;
        estResult.className = 'estimate-result good';
        estResult.innerHTML =
            '<strong>Estimated refund: ' + fmt(refund) + '</strong> — cancellation fee ' +
            (feePct * 100).toFixed(2).replace(/\.00$/, '') + '% (' + fmt(fee) + ') for ' + rule.label +
            ' cancelled ' + (hours > 11 ? 'more than 11 hours' : '2–11 hours') + ' before departure. ' +
            'Airport tax (PSC) refundable on request; card refunds may deduct ~3.5% bank charge.';
    }

    estBtn?.addEventListener('click', estimate);
    ['estFare', 'estClass', 'estHours'].forEach(id => {
        document.getElementById(id)?.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') estimate();
        });
    });
});
