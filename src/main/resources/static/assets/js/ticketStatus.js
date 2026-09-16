document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. NAVBAR SCROLL EFFECT (same as home.js)
    // ==========================================
    const navbar = document.getElementById('navbar');
    const navLogo = document.getElementById('navLogo');

    window.addEventListener('scroll', () => {
        if (window.scrollY > 50) {
            navbar.classList.add('scrolled');
            // Dark navbar site-wide: logo stays white, no swap on scroll
        } else {
            navbar.classList.remove('scrolled');
        }
    });

    // ==========================================
    // 2. MOBILE MENU (same as home.js)
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
    // 3b. BACK TO TOP (site-wide pattern — ticketStatus skips
    //     homeLogged.js, so the shared behavior is wired here)
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
    // 3. SCROLL REVEAL (same as home.js)
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
    // 4. MOCK BOOKING DATA
    // (Replace with a real API call later — search flow is already wired)
    // ==========================================
    const MOCK_BOOKINGS = {
        'YTR-2026-001234': {
            contact: {
                name: 'Aarav Sharma',
                email: 'aarav.sharma@example.com',
                phone: '+977 9812345678'
            },
            flight: {
                from: { code: 'KTM', city: 'Kathmandu', time: '08:30' },
                to: { code: 'PKR', city: 'Pokhara', time: '09:15' },
                date: '2026-09-20',
                flightNo: 'YT-214',
                duration: '45m',
                aircraft: 'ATR 72-500',
                cabin: 'Economy'
            },
            passengers: [
                { name: 'Aarav Sharma', type: 'ADT', nationality: 'Nepali', sector: 'KTM-PKR', ticketNo: 'YTR-2026-001234', cur: 'NPR', fare: 4200, fsc: 300, psc: 250, vat: 570, discount: 0, total: 5320, status: 'CONFIRMED' },
                { name: 'Sita Sharma', type: 'ADT', nationality: 'Nepali', sector: 'KTM-PKR', ticketNo: 'YTR-2026-001235', cur: 'NPR', fare: 4200, fsc: 300, psc: 250, vat: 570, discount: 200, total: 5120, status: 'CONFIRMED' },
                { name: 'Aarohi Sharma', type: 'CHD', nationality: 'Nepali', sector: 'KTM-PKR', ticketNo: 'YTR-2026-001236', cur: 'NPR', fare: 3100, fsc: 300, psc: 250, vat: 439, discount: 0, total: 4089, status: 'CONFIRMED' }
            ]
        },
        'YTRA2X': {
            contact: {
                name: 'Bibek Thapa',
                email: 'bibek.thapa@example.com',
                phone: '+977 9807654321'
            },
            flight: {
                from: { code: 'KTM', city: 'Kathmandu', time: '13:00' },
                to: { code: 'BWA', city: 'Bhairahawa', time: '13:50' },
                date: '2026-09-25',
                flightNo: 'YT-118',
                duration: '50m',
                aircraft: 'A320neo',
                cabin: 'Economy'
            },
            passengers: [
                { name: 'Bibek Thapa', type: 'ADT', nationality: 'Nepali', sector: 'KTM-BWA', ticketNo: 'YTR-2026-004451', cur: 'NPR', fare: 5100, fsc: 300, psc: 250, vat: 679, discount: 500, total: 5829, status: 'WAITLIST' },
                { name: 'Rita Thapa', type: 'ADT', nationality: 'Nepali', sector: 'KTM-BWA', ticketNo: 'YTR-2026-004452', cur: 'NPR', fare: 5100, fsc: 300, psc: 250, vat: 679, discount: 0, total: 6329, status: 'CONFIRMED' }
            ]
        }
    };

    // ==========================================
    // 5. SEARCH LOGIC
    // ==========================================
    const searchForm = document.getElementById('ticketSearchForm');
    const ticketInput = document.getElementById('ticketNoInput');
    const pnrInput = document.getElementById('pnrInput');
    const searchCard = document.querySelector('.search-card');
    const resultsWrapper = document.getElementById('tsResults');
    const searchBtn = document.getElementById('tsSearchBtn');

    const PNR_RE = /^[A-Z0-9]{5,8}$/;

    const formatMoney = (n) => n.toLocaleString('en-IN');
    const formatDate = (iso) => {
        const d = new Date(iso + 'T00:00:00');
        return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
    };
    const paxTypeBadge = (type) => {
        const map = { ADT: ['adt', 'Adult'], CHD: ['chd', 'Child'], INF: ['inf', 'Infant'] };
        const [cls, label] = map[type] || ['adt', type];
        return `<span class="pax-badge ${cls}">${label}</span>`;
    };
    const statusBadge = (status) => {
        const s = status.toLowerCase();
        return `<span class="status-badge ${s}">${status}</span>`;
    };

    function renderFlightDetails(flight) {
        const card = document.getElementById('flightDetailsCard');
        card.innerHTML = `
            <div class="flight-route">
                <div class="flight-endpoint">
                    <div class="airport-code">${flight.from.code}</div>
                    <div class="airport-city">${flight.from.city}</div>
                    <span class="dep-time"><i class="fa-solid fa-plane-departure"></i> ${flight.from.time}</span>
                </div>
                <div class="flight-path">
                    <span class="path-line"></span>
                    <span class="path-plane"><i class="fa-solid fa-plane"></i></span>
                    <span class="path-line"></span>
                </div>
                <div class="flight-endpoint">
                    <div class="airport-code">${flight.to.code}</div>
                    <div class="airport-city">${flight.to.city}</div>
                    <span class="dep-time"><i class="fa-solid fa-plane-arrival"></i> ${flight.to.time}</span>
                </div>
            </div>
            <div class="flight-meta">
                <span class="meta-chip"><i class="fa-solid fa-calendar"></i> ${formatDate(flight.date)}</span>
                <span class="meta-chip"><i class="fa-solid fa-plane-circle-check"></i> ${flight.flightNo}</span>
                <span class="meta-chip"><i class="fa-solid fa-clock"></i> ${flight.duration}</span>
                <span class="meta-chip"><i class="fa-solid fa-jet-fighter-up"></i> ${flight.aircraft}</span>
                <span class="meta-chip"><i class="fa-solid fa-chair"></i> ${flight.cabin}</span>
            </div>`;
    }

    function renderNotFound() {
        const card = document.getElementById('flightDetailsCard');
        card.innerHTML = `
            <div class="flight-not-found">
                <i class="fa-regular fa-face-frown"></i>
                <h3>No Booking Found</h3>
                <p>We couldn't find any booking for the details you entered. Please double-check and try again.</p>
            </div>`;
        document.getElementById('contactTableBody').innerHTML =
            '<tr class="empty-row"><td colspan="3" class="empty-cell">NO RECORD FOUND</td></tr>';
        document.getElementById('passengerTableBody').innerHTML =
            '<tr class="empty-row"><td colspan="14" class="empty-cell">NO RECORD FOUND</td></tr>';
    }

    function renderBooking(booking) {
        // Flight details
        renderFlightDetails(booking.flight);

        // Contact table
        const c = booking.contact;
        document.getElementById('contactTableBody').innerHTML = `
            <tr class="data-row">
                <td><strong>${c.name}</strong></td>
                <td>${c.email}</td>
                <td>${c.phone}</td>
            </tr>`;

        // Passenger table
        const rows = booking.passengers.map((p, i) => `
            <tr class="data-row">
                <td>${i + 1}</td>
                <td><strong>${p.name}</strong></td>
                <td>${paxTypeBadge(p.type)}</td>
                <td>${p.nationality}</td>
                <td>${p.sector}</td>
                <td>${p.ticketNo}</td>
                <td>${p.cur}</td>
                <td>${formatMoney(p.fare)}</td>
                <td>${formatMoney(p.fsc)}</td>
                <td>${formatMoney(p.psc)}</td>
                <td>${formatMoney(p.vat)}</td>
                <td>${formatMoney(p.discount)}</td>
                <td><strong>${formatMoney(p.total)}</strong></td>
                <td>${statusBadge(p.status)}</td>
            </tr>`).join('');
        document.getElementById('passengerTableBody').innerHTML = rows;
    }

    if (searchForm) {
        searchForm.addEventListener('submit', (e) => {
            e.preventDefault();

            const ticket = ticketInput.value.trim();
            const pnr = pnrInput.value.trim();

            // Validation: need at least one field
            if (!ticket && !pnr) {
                searchCard.classList.remove('input-error');
                void searchCard.offsetWidth; // restart shake animation
                searchCard.classList.add('input-error');
                ticketInput.focus();
                return;
            }
            searchCard.classList.remove('input-error');

            // Key: PNR lookup first, then ticket number
            let booking = null;
            if (pnr && PNR_RE.test(pnr.toUpperCase())) {
                booking = MOCK_BOOKINGS[pnr.toUpperCase()] || null;
            }
            if (!booking && ticket) {
                booking = MOCK_BOOKINGS[ticket.toUpperCase()] || null;
            }

            // Loading state
            const originalBtnHTML = searchBtn.innerHTML;
            searchBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Searching...';
            searchBtn.disabled = true;

            // Simulated API delay (replace with fetch() to real API later)
            setTimeout(() => {
                if (booking) {
                    renderBooking(booking);
                } else {
                    renderNotFound();
                }

                resultsWrapper.hidden = false;
                resultsWrapper.scrollIntoView({ behavior: 'smooth', block: 'start' });

                searchBtn.innerHTML = originalBtnHTML;
                searchBtn.disabled = false;
            }, 700);
        });

        // Clear error state on typing
        [ticketInput, pnrInput].forEach(inp => {
            inp?.addEventListener('input', () => searchCard.classList.remove('input-error'));
        });
    }
});
