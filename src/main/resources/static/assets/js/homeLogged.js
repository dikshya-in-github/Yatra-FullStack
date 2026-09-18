document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. NAVBAR SCROLL EFFECT
    // ==========================================
    const navbar = document.getElementById('navbar');
    const navLogo = document.getElementById('navLogo');

    if (navbar) {
        window.addEventListener('scroll', () => {
            if (window.scrollY > 50) {
                navbar.classList.add('scrolled');
                // Dark navbar site-wide: logo stays white, no swap on scroll
            } else {
                navbar.classList.remove('scrolled');
            }
        });
    }

    // ==========================================
    // 2. MOBILE MENU
    // ==========================================
    const mobileBtn = document.getElementById('mobileMenuBtn');
    const mobileMenu = document.getElementById('mobileMenu');

    if (mobileBtn && mobileMenu) {
        mobileBtn.addEventListener('click', () => {
            mobileMenu.classList.toggle('open');
            const icon = mobileBtn.querySelector('i');
            icon.classList.toggle('fa-bars');
            icon.classList.toggle('fa-xmark');
        });

        mobileMenu.querySelectorAll('a').forEach(link => {
            link.addEventListener('click', () => {
                mobileMenu.classList.remove('open');
                mobileBtn.querySelector('i').classList.add('fa-bars');
                mobileBtn.querySelector('i').classList.remove('fa-xmark');
            });
        });
    }

    // ==========================================
    // 3. TAB SWITCHING
    // (FIX: previously only handled 'book' — every other tab, including
    // Group Booking, fell through to 'statusTab'. Now maps generically.)
    // ==========================================
    const tabBtns = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            tabBtns.forEach(b => b.classList.remove('active'));
            tabContents.forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            const targetId = `${btn.dataset.tab}Tab`;
            const targetEl = document.getElementById(targetId);
            if (targetEl) targetEl.classList.add('active');
        });
    });

    // ==========================================
    // 4. ROUND TRIP TOGGLE (anti-shift, class-based)
    // ==========================================
    function setupTripToggle({ toggleId, retBlockId, retInputId, oneWayId, roundId }) {
        const toggle = document.getElementById(toggleId);
        const retBlock = document.getElementById(retBlockId);
        const retInput = document.getElementById(retInputId);
        const oneWayLabel = document.getElementById(oneWayId);
        const roundLabel = document.getElementById(roundId);
        if (!toggle || !retBlock) return;

        const sync = () => {
            const isRound = toggle.checked;
            retBlock.classList.toggle('field-hidden', !isRound); // keeps grid space = no shift
            if (retInput) {
                retInput.required = isRound;
                if (!isRound) retInput.value = '';
            }
            if (oneWayLabel) oneWayLabel.classList.toggle('active', !isRound);
            if (roundLabel) roundLabel.classList.toggle('active', isRound);
        };

        toggle.addEventListener('change', sync);
        sync(); // set correct initial state on load
    }

    setupTripToggle({ toggleId: 'roundTripToggle', retBlockId: 'retDateBlock', retInputId: 'retDate', oneWayId: 'oneWayLabel', roundId: 'roundTripLabel' });
    setupTripToggle({ toggleId: 'groupRoundTripToggle', retBlockId: 'groupRetDateBlock', retInputId: 'groupRetDate', oneWayId: 'groupOneWayLabel', roundId: 'groupRoundTripLabel' });

    // ==========================================
    // 5. ROUTE DROPDOWNS — §3's two selection rules
    // ==========================================
    /* The rules themselves live in cityPair.js. They were moved, not copied,
       because fix-plan §4's "Modify Flight" popup has to reuse them on
       searchFlight.html — and that page deliberately does NOT load this file
       (doing so would double-bind #mobileMenuBtn; see its own script block), so
       there was no way to reach them from the page that now needs them. One
       implementation, two callers. */
    /* Only home.html and homeLogged.html carry these dropdowns, and only they
       load cityPair.js. Every other page in the site loads THIS file for the
       navbar alone, so an unconditional call threw here and killed the rest of
       this handler (the passenger modal and scroll reveal below never ran).
       The call is guarded on the markup rather than on the module: a page that
       HAS the selects still requires cityPair.js and still fails loudly without it. */
    const routePairs = [
        ['swapRouteBtn', 'origin', 'destination'],
        ['groupSwapRouteBtn', 'groupOrigin', 'groupDestination']
    ];
    if (routePairs.some(pair => document.getElementById(pair[1]) && document.getElementById(pair[2]))) {
        YatraCityPair.setupAll(routePairs);
    }

    const originSelect = document.getElementById('origin');
    const destSelect = document.getElementById('destination');

    // ==========================================
    // 6. PASSENGER MODAL
    // ==========================================
    const passengerTrigger = document.getElementById('passengerTrigger');
    const passengerModal = document.getElementById('passengerModal');
    const closeModalBtn = document.getElementById('closePassengerModal');
    const savePassengersBtn = document.getElementById('savePassengers');
    const passengerDisplay = document.getElementById('passengerDisplay');

    let counts = { adult: 1, child: 0, infant: 0 };

    if (passengerTrigger && passengerModal) {
        passengerTrigger.addEventListener('click', () => passengerModal.classList.add('open'));
        closeModalBtn.addEventListener('click', () => passengerModal.classList.remove('open'));
        passengerModal.addEventListener('click', (e) => {
            if (e.target === passengerModal) passengerModal.classList.remove('open');
        });

        document.querySelectorAll('.count-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const target = btn.dataset.target;
                const isPlus = btn.classList.contains('plus');
                const valSpan = document.getElementById(`${target}Count`);
                let current = parseInt(valSpan.textContent);

                if (isPlus) { if (current < 9) current++; }
                else { const min = target === 'adult' ? 1 : 0; if (current > min) current--; }

                valSpan.textContent = current;
                counts[target] = current;
            });
        });

        savePassengersBtn.addEventListener('click', () => {
            const total = counts.adult + counts.child + counts.infant;
            passengerDisplay.textContent = `${total} Passenger${total > 1 ? 's' : ''}`;
            passengerModal.classList.remove('open');
        });
    }

    // ==========================================
    // 7. SCROLL REVEAL ANIMATION
    // ==========================================
    const revealElements = document.querySelectorAll('.reveal');
    const revealObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('active');
                revealObserver.unobserve(entry.target);
            }
        });
    }, { threshold: 0.15, rootMargin: '0px 0px -50px 0px' });

    revealElements.forEach(el => revealObserver.observe(el));

    // ==========================================
    // 8. NUMBER COUNTER ANIMATION
    // ==========================================
    const statNumbers = document.querySelectorAll('.stat-number');
    const counterObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const el = entry.target;
                const target = parseInt(el.dataset.target);
                const suffix = el.textContent.includes('%') ? '%' : el.textContent.includes('K+') ? 'K+' : '';
                const duration = 2000;
                const start = performance.now();

                function updateCounter(currentTime) {
                    const elapsed = currentTime - start;
                    const progress = Math.min(elapsed / duration, 1);
                    const eased = 1 - Math.pow(1 - progress, 3);
                    const current = Math.floor(eased * target);
                    el.textContent = current.toLocaleString() + suffix;
                    if (progress < 1) requestAnimationFrame(updateCounter);
                    else el.textContent = target.toLocaleString() + suffix;
                }
                requestAnimationFrame(updateCounter);
                counterObserver.unobserve(el);
            }
        });
    }, { threshold: 0.5 });

    statNumbers.forEach(el => counterObserver.observe(el));

    // ==========================================
    // 9. DATE PICKER MIN DATE (Book a Flight + Group Booking)
    // ==========================================
    const today = new Date().toISOString().split('T')[0];

    const depDateInput = document.getElementById('depDate');
    const retDateInput = document.getElementById('retDate');
    if (depDateInput) depDateInput.min = today;
    depDateInput?.addEventListener('change', () => {
        if (retDateInput) retDateInput.min = depDateInput.value;
    });

    const groupDepDateInput = document.getElementById('groupDepDate');
    const groupRetDateInput = document.getElementById('groupRetDate');
    if (groupDepDateInput) groupDepDateInput.min = today;
    groupDepDateInput?.addEventListener('change', () => {
        if (groupRetDateInput) groupRetDateInput.min = groupDepDateInput.value;
    });

    // ==========================================
    // 10. FLIGHT SEARCH FORM SUBMISSION
    // ==========================================
    const roundTripToggle = document.getElementById('roundTripToggle');
    const flightForm = document.getElementById('flightSearchForm');

    if (flightForm) {
        flightForm.addEventListener('submit', (e) => {
            e.preventDefault();

            /* Shared client-side rules (spec §35 validation.js). The browser's
               native `required` already covers the empty controls; the shared
               layer adds what native validation cannot: a same-city route and
               a return date that precedes the departure. */
            const check = validateSearchForm();
            if (!check.valid) {
                if (check.firstInvalid) check.firstInvalid.focus({ preventScroll: false });
                return;
            }

            const searchData = {
                origin: originSelect?.value,
                destination: destSelect?.value,
                nationality: document.getElementById('nationality')?.value,
                departureDate: depDateInput?.value,
                returnDate: retDateInput?.value || null,
                tripType: roundTripToggle?.checked ? 'roundtrip' : 'oneway',
                passengers: counts,
                cabinClass: 'economy',
                promoCode: document.getElementById('promoCode')?.value || null
            };
            console.log('Search Data:', searchData);
            sessionStorage.setItem('flightSearchData', JSON.stringify(searchData));

            /* A new search starts a new booking flow, so the previous flow's hold
               goes with it. More necessary now than it was with a counter: the
               storage holds an absolute instant, so a leftover one is inherited
               by booking.html as an already-expired countdown rather than as a
               number that merely looked stale. Guarded because home.html loads
               this script without hold.js. */
            if (typeof YatraHold !== 'undefined') YatraHold.clear();

            const searchBtn = flightForm.querySelector('.search-btn');
            if (searchBtn) {
                searchBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Searching...';
                searchBtn.disabled = true;
            }
            setTimeout(() => {
                // Redirect to the flight listing (searchFlight.html) with URL params it reads:
                // ?from=&to=&date=&pax= (searchFlight.js parses these on load).
                const params = new URLSearchParams();
                if (searchData.origin) params.set('from', searchData.origin);
                if (searchData.destination) params.set('to', searchData.destination);
                if (searchData.departureDate) params.set('date', searchData.departureDate);
                const totalPax = (searchData.passengers?.adult || 0) + (searchData.passengers?.child || 0);
                params.set('pax', String(totalPax || 1));
                window.location.href = './searchFlight.html?' + params.toString();
            }, 800);
        });
    }

    // ==========================================
    // 11. GROUP BOOKING: "ADD MORE DETAILS" PROGRESSIVE DISCLOSURE
    // ==========================================
    const groupActionBtn = document.getElementById('groupActionBtn');
    const groupExtraFields = document.getElementById('groupExtraFields');
    const groupBookingForm = document.getElementById('groupBookingForm');
    const groupLeaderName = document.getElementById('groupLeaderName');
    const groupContact = document.getElementById('groupContact');

    if (groupActionBtn && groupExtraFields) {
        groupActionBtn.addEventListener('click', () => {
            const isHidden = groupExtraFields.style.display === 'none' || !groupExtraFields.style.display;
            if (isHidden) {
                groupExtraFields.style.display = 'flex';
                // Only require these once they're actually visible
                if (groupLeaderName) groupLeaderName.required = true;
                if (groupContact) groupContact.required = true;
                groupActionBtn.innerHTML = 'Request Group Quote <i class="fa-solid fa-paper-plane"></i>';
                groupActionBtn.type = 'submit';
                groupLeaderName?.focus();
            }
        });
    }

    if (groupBookingForm) {
        groupBookingForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const groupData = {
                origin: document.getElementById('groupOrigin')?.value,
                destination: document.getElementById('groupDestination')?.value,
                nationality: document.getElementById('groupNationality')?.value,
                travellers: document.getElementById('groupTravellers')?.value,
                departureDate: groupDepDateInput?.value,
                returnDate: groupRetDateInput?.value || null,
                tripType: document.getElementById('groupRoundTripToggle')?.checked ? 'roundtrip' : 'oneway',
                groupLeaderName: groupLeaderName?.value,
                contact: groupContact?.value
            };
            console.log('Group Booking Data:', groupData);

            if (groupActionBtn) {
                groupActionBtn.innerHTML = '<i class="fa-solid fa-check"></i> Request Sent!';
                groupActionBtn.disabled = true;
            }
        });
    }

    // ==========================================
    // 12. BACK TO TOP (site-wide — appears on pages
    //     that include the #backToTop button markup)
    // ==========================================
    const backToTop = document.getElementById('backToTop');
    if (backToTop) {
        const toggleBackToTop = () => backToTop.classList.toggle('show', window.scrollY > 500);
        window.addEventListener('scroll', toggleBackToTop, { passive: true });
        toggleBackToTop();
        backToTop.addEventListener('click', () => {
            const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' });
        });
    }

    // ==========================================
    // 13. SESSION CHROME (navbar user chip + working "Log Out")
    //     Only runs on pages that load auth.js; a page without it
    //     keeps the static markup — no error, no visual change.
    //     The wizard pages (searchFlight/booking/payment/eticket/
    //     ticketStatus) have their own navbar script instead of this
    //     file, so they call YatraAuth.renderSessionChrome() directly.
    // ==========================================
    if (typeof YatraAuth !== 'undefined') {
        YatraAuth.renderSessionChrome();
    }

    // ==========================================
    // 14. DRAGGABLE HORIZONTAL SLIDER
    // ==========================================
    const slider = document.querySelector('.dest-slider-container');
    let isDown = false;
    let startX;
    let scrollLeft;

    if (slider) {
        slider.addEventListener('mousedown', (e) => {
            isDown = true;
            slider.classList.add('active');
            startX = e.pageX - slider.offsetLeft;
            scrollLeft = slider.scrollLeft;
        });
        slider.addEventListener('mouseleave', () => { isDown = false; slider.classList.remove('active'); });
        slider.addEventListener('mouseup', () => { isDown = false; slider.classList.remove('active'); });
        slider.addEventListener('mousemove', (e) => {
            if (!isDown) return;
            e.preventDefault();
            const x = e.pageX - slider.offsetLeft;
            const walk = (x - startX) * 2; // Scroll speed multiplier
            slider.scrollLeft = scrollLeft - walk;
        });
    }
});