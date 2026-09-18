/* =========================================
   YATRA ADMIN — BOOKINGS PAGE JS
   Read-mostly management of customer bookings
   (Master Plan §2.1 #5). The storefront writes
   completed eSewa checkouts into localStorage
   `yatra_bookings` (assets/js/esewaConfirm.js);
   this page views/manages them. Demo seeds are
   merged in only once so live customer records
   are never overwritten.

   Rules baked in:
   - Cancel = status change (Confirmed →
     Cancelled + payment flagged for refund
     follow-up). Seats stay counted on the
     flight — only the backend's future
     payment/refund flow completes them.
   - Payment status is never edited here
     (admin-payments.html will own refunds).
   - Hard delete exists only for the `_seed`
     demo records; live bookings are kept.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const BOOKING_KEY = 'yatra_bookings'; // owned by the storefront (esewaConfirm.js)
  const SEED_FLAG = 'yatra_bookings_seeded_v1';
  const PAGE_SIZE = 8;

  /* ---------- Demo seed data (merged in once; marked _seed for reset) ---------- */
  const SEED_BOOKINGS = [
    {
      id: 'BKG10000001', _seed: true, pnr: 'YTRA26', ticketNo: '784-244829135701',
      status: 'Confirmed', paymentStatus: 'Paid',
      customer: 'Anju Karki', email: 'anju.karki@example.com', phone: '9841234567',
      passengers: [
        { title: 'Ms', firstName: 'Anju', lastName: 'Karki', type: 'ADT', nationality: 'Nepali' },
        { title: 'Mr', firstName: 'Rohan', lastName: 'Karki', type: 'CHD', nationality: 'Nepali' }
      ],
      flight: {
        flightNo: 'U4 951', airline: { name: 'Buddha Air', iata: 'U4' },
        from: 'KTM', to: 'PKR', date: '2026-09-16', depart: '06:50', arrive: '07:35',
        flightClass: 'E Class', refundable: false, pricePerPassenger: 8299.99, passengerCount: 2
      },
      amount: 16599.98, productAmount: 16599.98,
      payment: { method: 'eSewa', txnId: '9A48291357', paidAt: '2026-09-14T18:22:00.000Z' },
      createdAt: '2026-09-14T18:22:00.000Z'
    },
    {
      id: 'BKG10000002', _seed: true, pnr: 'YTRA48', ticketNo: '784-246715398202',
      status: 'Confirmed', paymentStatus: 'Paid',
      customer: 'Bikash Shrestha', email: 'bikash.s@example.com', phone: '9818765432',
      passengers: [
        { title: 'Mr', firstName: 'Bikash', lastName: 'Shrestha', type: 'ADT', nationality: 'Nepali' }
      ],
      flight: {
        flightNo: 'YT 958', airline: { name: 'Yeti Airlines', iata: 'YT' },
        from: 'KTM', to: 'PKR', date: '2026-09-16', depart: '09:55', arrive: '10:40',
        flightClass: 'Y Class', refundable: true, pricePerPassenger: 13299.99, passengerCount: 1
      },
      amount: 13299.99, productAmount: 13299.99,
      payment: { method: 'eSewa', txnId: '9A67153982', paidAt: '2026-09-14T19:05:00.000Z' },
      createdAt: '2026-09-14T19:05:00.000Z'
    },
    {
      id: 'BKG10000003', _seed: true, pnr: 'YTRA71', ticketNo: '784-249024671503',
      status: 'Cancelled', paymentStatus: 'Refunded',
      customer: 'Sanjay Thapa Magar', email: 'sanjay.tm@example.com', phone: '9801122334',
      passengers: [
        { title: 'Mr', firstName: 'Sanjay', lastName: 'Thapa Magar', type: 'ADT', nationality: 'Nepali' },
        { title: 'Mrs', firstName: 'Mina', lastName: 'Thapa Magar', type: 'ADT', nationality: 'Nepali' },
        { title: 'Miss', firstName: 'Sujata', lastName: 'Thapa Magar', type: 'CHD', nationality: 'Nepali' }
      ],
      flight: {
        flightNo: 'S3 507', airline: { name: 'Shree Airlines', iata: 'S3' },
        from: 'KTM', to: 'BIR', date: '2026-09-15', depart: '11:35', arrive: '12:20',
        flightClass: 'D Class', refundable: false, pricePerPassenger: 10099.99, passengerCount: 3
      },
      amount: 30299.97, productAmount: 30299.97,
      payment: { method: 'eSewa', txnId: '9A90246715', paidAt: '2026-09-13T14:41:00.000Z' },
      createdAt: '2026-09-13T14:41:00.000Z'
    },
    {
      id: 'BKG10000004', _seed: true, pnr: 'YTRA33', ticketNo: '784-241159802674',
      status: 'Confirmed', paymentStatus: 'Paid',
      customer: 'Prakriti Rai', email: 'prakriti.rai@example.com', phone: '9856234781',
      passengers: [
        { title: 'Ms', firstName: 'Prakriti', lastName: 'Rai', type: 'ADT', nationality: 'Nepali' }
      ],
      flight: {
        flightNo: 'ST 221', airline: { name: 'Sita Air', iata: 'ST' },
        from: 'KTM', to: 'KEP', date: '2026-09-17', depart: '12:50', arrive: '13:45',
        flightClass: 'E Class', refundable: false, pricePerPassenger: 9199.99, passengerCount: 1
      },
      amount: 9199.99, productAmount: 9199.99,
      payment: { method: 'eSewa', txnId: '9A11598026', paidAt: '2026-09-14T20:12:00.000Z' },
      createdAt: '2026-09-14T20:12:00.000Z'
    },
    {
      id: 'BKG10000005', _seed: true, pnr: 'YTRA59', ticketNo: '784-245538114095',
      status: 'Confirmed', paymentStatus: 'Paid',
      customer: 'Nirmala Gurung', email: 'nirmala.g@example.com', phone: '9845612309',
      passengers: [
        { title: 'Mrs', firstName: 'Nirmala', lastName: 'Gurung', type: 'ADT', nationality: 'Nepali' },
        { title: 'Mr', firstName: 'Tenzing', lastName: 'Gurung', type: 'ADT', nationality: 'Nepali' },
        { title: 'Miss', firstName: 'Anisha', lastName: 'Gurung', type: 'CHD', nationality: 'Nepali' },
        { title: 'Master', firstName: 'Prabal', lastName: 'Gurung', type: 'CHD', nationality: 'Nepali' }
      ],
      flight: {
        flightNo: 'U4 953', airline: { name: 'Buddha Air', iata: 'U4' },
        from: 'KTM', to: 'BWA', date: '2026-09-16', depart: '15:10', arrive: '16:00',
        flightClass: 'C Class', refundable: false, pricePerPassenger: 10999.99, passengerCount: 4
      },
      amount: 43999.96, productAmount: 43999.96,
      payment: { method: 'eSewa', txnId: '9A55381140', paidAt: '2026-09-14T21:37:00.000Z' },
      createdAt: '2026-09-14T21:37:00.000Z'
    },
    {
      id: 'BKG10000006', _seed: true, pnr: 'YTRA84', ticketNo: '784-243367412956',
      status: 'Confirmed', paymentStatus: 'Paid',
      customer: 'Dipesh Neupane', email: 'dipesh.n@example.com', phone: '9861250974',
      passengers: [
        { title: 'Mr', firstName: 'Dipesh', lastName: 'Neupane', type: 'ADT', nationality: 'Nepali' }
      ],
      flight: {
        flightNo: 'YT 962', airline: { name: 'Yeti Airlines', iata: 'YT' },
        from: 'PKR', to: 'KTM', date: '2026-09-14', depart: '17:25', arrive: '18:10',
        flightClass: 'A Class', refundable: true, pricePerPassenger: 13299.99, passengerCount: 1
      },
      amount: 13299.99, productAmount: 13299.99,
      payment: { method: 'eSewa', txnId: '9A33674129', paidAt: '2026-09-13T09:18:00.000Z' },
      createdAt: '2026-09-13T09:18:00.000Z'
    },
    {
      id: 'BKG10000007', _seed: true, pnr: 'YTRA92', ticketNo: '784-247741290357',
      status: 'Confirmed', paymentStatus: 'Paid',
      customer: 'Sarita Lamichhane', email: 'sarita.l@example.com', phone: '9849056172',
      passengers: [
        { title: 'Mrs', firstName: 'Sarita', lastName: 'Lamichhane', type: 'ADT', nationality: 'Nepali' },
        { title: 'Mr', firstName: 'Hari', lastName: 'Lamichhane', type: 'ADT', nationality: 'Nepali' }
      ],
      flight: {
        flightNo: 'U4 951', airline: { name: 'Buddha Air', iata: 'U4' },
        from: 'KTM', to: 'PKR', date: '2026-09-18', depart: '06:50', arrive: '07:35',
        flightClass: 'B Class', refundable: false, pricePerPassenger: 11299.99, passengerCount: 2
      },
      amount: 22599.98, productAmount: 22599.98,
      payment: { method: 'eSewa', txnId: '9A77412903', paidAt: '2026-09-15T07:54:00.000Z' },
      createdAt: '2026-09-15T07:54:00.000Z'
    },
    {
      id: 'BKG10000008', _seed: true, pnr: 'YTRA15', ticketNo: '784-244209357118',
      status: 'Confirmed', paymentStatus: 'Paid',
      customer: 'Kabin Bhandari', email: 'kabin.b@example.com', phone: '9807341285',
      passengers: [
        { title: 'Mr', firstName: 'Kabin', lastName: 'Bhandari', type: 'ADT', nationality: 'Nepali' }
      ],
      flight: {
        flightNo: 'S3 507', airline: { name: 'Shree Airlines', iata: 'S3' },
        from: 'KTM', to: 'BIR', date: '2026-09-17', depart: '11:35', arrive: '12:20',
        flightClass: 'Y Class', refundable: true, pricePerPassenger: 13299.99, passengerCount: 1
      },
      amount: 13299.99, productAmount: 13299.99,
      payment: { method: 'eSewa', txnId: '9A42093571', paidAt: '2026-09-15T11:26:00.000Z' },
      createdAt: '2026-09-15T11:26:00.000Z'
    }
  ];

  /* ---------- State (mock "repository") ---------- */
  let bookings = []; // initial read goes through the API layer (item 16)
  let state = { search: '', bookingStatus: 'ALL', payStatus: 'ALL', page: 1 };
  let confirmAction = null; // queue for the styled confirm modal
  let currentDetailId = null;

  function loadBookings() {
    let list = [];
    try {
      const raw = localStorage.getItem(BOOKING_KEY);
      list = raw ? JSON.parse(raw) : [];
      if (!Array.isArray(list)) list = [];
    } catch (err) { list = []; }

    /* Merge seed demo data exactly once — live storefront records are
       never overwritten or duplicated (merge is idempotent by id). */
    if (!localStorage.getItem(SEED_FLAG)) {
      const existing = new Set(list.map((b) => b.id));
      SEED_BOOKINGS.forEach((s) => {
        if (!existing.has(s.id)) list.push({ ...s });
      });
      try { localStorage.setItem(BOOKING_KEY, JSON.stringify(list)); } catch (err) { /* quota */ }
      try { localStorage.setItem(SEED_FLAG, '1'); } catch (err) { /* quota */ }
    }

    list.forEach(normalizeRecord);
    return list;
  }

  /* Older/edge storefront records may lack newer fields — normalize for display. */
  function normalizeRecord(b) {
    if (!b || typeof b !== 'object') return;
    if (!b.id) b.id = 'BKG' + Date.now().toString().slice(-8);
    if (typeof b.pnr !== 'string') b.pnr = '';
    if (!b.status) b.status = 'Confirmed';
    if (!b.paymentStatus) b.paymentStatus = 'Paid';
    if (!b.payment || typeof b.payment !== 'object') {
      b.payment = { method: 'eSewa', txnId: '', paidAt: b.createdAt || '' };
    }
    if (!b.payment.method) b.payment.method = 'eSewa';
    if (!Array.isArray(b.passengers)) b.passengers = [];
    if (!b.flight || typeof b.flight !== 'object') b.flight = {};
    // searchFlight's airline shape uses `code` — align with the seed `iata`.
    if (b.flight.airline && !b.flight.airline.iata && b.flight.airline.code) {
      b.flight.airline.iata = b.flight.airline.code;
    }
  }

  function saveBookings() {
    try {
      localStorage.setItem(BOOKING_KEY, JSON.stringify(bookings));
    } catch (err) {
      showToast('Could not save — storage quota reached.', 'error');
    }
  }

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page,
     which also creates the #toast element it writes to. */

  /* ---------- Filtering + pagination ---------- */
  function filtered() {
    const q = state.search.trim().toLowerCase();
    return bookings.filter((b) => {
      const f = b.flight || {};
      const matchesQ =
        !q ||
        (b.pnr || '').toLowerCase().includes(q) ||
        b.id.toLowerCase().includes(q) ||
        (b.customer || '').toLowerCase().includes(q) ||
        (f.flightNo || '').toLowerCase().includes(q);
      const matchesBooking =
        state.bookingStatus === 'ALL' || b.status === state.bookingStatus;
      const matchesPay =
        state.payStatus === 'ALL' || b.paymentStatus === state.payStatus;
      return matchesQ && matchesBooking && matchesPay;
    });
  }

  const totalPages = () => Math.max(1, Math.ceil(filtered().length / PAGE_SIZE));

  function clampPage() {
    state.page = Math.min(Math.max(1, state.page), totalPages());
  }

  /* ---------- Render helpers ---------- */
  function fmtNPR(n) {
    return 'NPR ' + Number(n || 0).toLocaleString('en-US', {
      minimumFractionDigits: 2, maximumFractionDigits: 2
    });
  }

  function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso.length > 10 ? iso : iso + 'T00:00:00');
    return isNaN(d) ? iso
      : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  function fmtDateTime(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return isNaN(d) ? iso
      : d.toLocaleString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  function to12(hhmm) {
    if (!hhmm) return '—';
    const p = hhmm.split(':'), h = +p[0];
    return ((h + 11) % 12 + 1) + ':' + p[1] + ' ' + (h >= 12 ? 'PM' : 'AM');
  }

  const bookingBadge = (s) => (s === 'Confirmed' ? 'badge-success' : 'badge-danger');
  const payBadge = (s) => (s === 'Paid' ? 'badge-success' : 'badge-info');

  /* ---------- Table render ---------- */
  function render() {
    clampPage();
    const rows = filtered();
    const start = (state.page - 1) * PAGE_SIZE;
    const pageRows = rows.slice(start, start + PAGE_SIZE);

    const tbody = $('#bookingTableBody');
    tbody.innerHTML = pageRows
      .map((b) => {
        const f = b.flight || {};
        const pax = f.passengerCount || (b.passengers ? b.passengers.length : 1);
        const canCancel = b.status === 'Confirmed';
        const canDelete = !!b._seed;
        return `
      <tr>
        <td><strong>${escapeHtml(b.pnr || '—')}</strong><span class="cell-sub">${escapeHtml(b.id)}</span></td>
        <td>
          <strong>${escapeHtml(b.customer || '—')}</strong>
          <span class="cell-sub">${escapeHtml(b.phone || '')}</span>
        </td>
        <td><strong>${escapeHtml(f.flightNo || '—')}</strong>
          <span class="cell-sub">${escapeHtml((f.airline && f.airline.name) || '')}</span></td>
        <td>
          <strong>${escapeHtml(f.from && f.to ? `${f.from} → ${f.to}` : '—')}</strong>
          <span class="cell-sub">${escapeHtml(fmtDate(f.date))}</span>
        </td>
        <td>${pax}</td>
        <td><strong>${fmtNPR(b.amount)}</strong></td>
        <td><span class="badge ${payBadge(b.paymentStatus)}">${escapeHtml(b.paymentStatus)}</span>
          <span class="cell-sub">${escapeHtml((b.payment && b.payment.method) || '')}</span></td>
        <td><span class="badge ${bookingBadge(b.status)}">${escapeHtml(b.status)}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-view="${b.id}" aria-label="View ${escapeHtml(b.pnr || b.id)}"><i class="fa-solid fa-eye"></i></button>
            ${canCancel ? `<button type="button" class="icon-btn" data-cancel="${b.id}" aria-label="Cancel booking ${escapeHtml(b.pnr)}"><i class="fa-solid fa-ban"></i></button>` : ''}
            ${canDelete ? `<button type="button" class="icon-btn icon-btn-danger" data-delete="${b.id}" aria-label="Delete demo booking ${escapeHtml(b.id)}"><i class="fa-solid fa-trash"></i></button>` : ''}
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = rows.length > 0;
    $('#resultCount').textContent = `${rows.length} booking${rows.length === 1 ? '' : 's'}`;
    renderPagination(rows.length);
  }

  function renderPagination(totalRows) {
    const pages = totalPages();
    $('#pageInfo').textContent = totalRows
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(state.page * PAGE_SIZE, totalRows)} of ${totalRows} bookings`
      : 'No bookings';

    const btns = $('#pageBtns');
    btns.innerHTML = '';
    if (pages <= 1) return;

    const mk = (label, page, opts = {}) => {
      const b = document.createElement('button');
      b.className = 'page-btn' + (opts.active ? ' active' : '');
      b.innerHTML = opts.icon ? `<i class="fa-solid ${opts.icon}"></i>` : label;
      b.setAttribute('aria-label', opts.label || `Page ${label}`);
      b.addEventListener('click', () => {
        state.page = page;
        render();
      });
      return b;
    };

    btns.appendChild(mk('', state.page - 1, { icon: 'fa-chevron-left', label: 'Previous page' }));
    for (let p = 1; p <= pages; p++) {
      btns.appendChild(mk(p, p, { active: p === state.page }));
    }
    btns.appendChild(mk('', state.page + 1, { icon: 'fa-chevron-right', label: 'Next page' }));
  }

  /* ---------- Toolbar events ---------- */
  $('#bookingSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    render();
  });

  $('#bookingStatusFilter').addEventListener('change', (e) => {
    state.bookingStatus = e.target.value;
    state.page = 1;
    render();
  });

  $('#payStatusFilter').addEventListener('change', (e) => {
    state.payStatus = e.target.value;
    state.page = 1;
    render();
  });

  /* ---------- Styled confirm modal (shared pattern, replaces confirm()) ---------- */
  const confirmModal = $('#confirmModal');

  function askConfirm(opts) {
    confirmAction = opts;
    $('#confirmTitle').textContent = opts.title || 'Are you sure?';
    $('#confirmMsg').textContent = opts.message;
    $('#confirmYes').innerHTML =
      `<i class="fa-solid ${opts.icon || 'triangle-exclamation'}"></i> ${opts.yesLabel || 'Confirm'}`;
    confirmModal.hidden = false;
  }

  function hideConfirm() {
    confirmModal.hidden = true;
    confirmAction = null;
  }

  $('#confirmClose').addEventListener('click', hideConfirm);
  $('#confirmNo').addEventListener('click', hideConfirm);
  confirmModal.addEventListener('click', (e) => {
    if (e.target === confirmModal) hideConfirm();
  });
  $('#confirmYes').addEventListener('click', () => {
    const act = confirmAction;
    hideConfirm();
    if (act && typeof act.onYes === 'function') act.onYes();
  });

  /* ---------- Booking actions ---------- */
  function cancelBooking(b) {
    askConfirm({
      title: 'Cancel this booking?',
      message: `Booking ${b.pnr || b.id} for ${b.customer || 'this customer'} will be marked Cancelled. Seats stay counted on the flight; the refund itself is completed by the payments flow, not here.`,
      yesLabel: 'Cancel booking',
      icon: 'ban',
      onYes: () => {
        b.status = 'Cancelled';
        saveBookings();
        render();
        showToast(`${b.pnr || b.id} cancelled — flagged for refund follow-up.`, 'success');
      }
    });
  }

  /* Hard delete exists only for demo seeds. If the booking was cancelled,
     give its seats back to the mock flights store so demo numbers add up. */
  function deleteBooking(b) {
    askConfirm({
      title: 'Delete demo booking?',
      message: `Demo booking ${b.id} (${b.pnr || 'no PNR'}) will be removed permanently. Live customer bookings are never deletable here.`,
      yesLabel: 'Delete',
      icon: 'trash',
      onYes: () => {
        if (b.status === 'Cancelled') freeSeatsFor(b);
        bookings = bookings.filter((x) => x.id !== b.id);
        saveBookings();
        render();
        showToast(`${b.id} removed from the demo data.`, 'success');
      }
    });
  }

  function freeSeatsFor(b) {
    try {
      const f = b.flight || {};
      const pax = f.passengerCount || (b.passengers ? b.passengers.length : 1);
      const flights = JSON.parse(localStorage.getItem('yatra_admin_flights') || '[]');
      const match = flights.find(
        (fl) => fl && fl.no === f.flightNo && fl.from === f.from && fl.to === f.to
      );
      if (match && typeof match.bookedSeats === 'number') {
        match.bookedSeats = Math.max(0, match.bookedSeats - pax);
        localStorage.setItem('yatra_admin_flights', JSON.stringify(flights));
      }
    } catch (err) { /* demo helper only — never block the delete */ }
  }

  /* ---------- Reset demo data ---------- */
  $('#resetBtn').addEventListener('click', () => {
    askConfirm({
      title: 'Reset demo bookings?',
      message: 'The 8 demo seed bookings are removed and re-added fresh. Live customer bookings saved from the storefront are kept.',
      yesLabel: 'Reset demo',
      icon: 'rotate-left',
      onYes: () => {
        bookings = bookings.filter((b) => !b._seed);
        SEED_BOOKINGS.forEach((s) => bookings.push({ ...s }));
        saveBookings();
        state.page = 1;
        render();
        showToast('Demo bookings reset — live bookings kept.', 'success');
      }
    });
  });

  /* ---------- Booking detail modal (read-only; mirrors eticket.js) ---------- */
  const detailModal = $('#bookingModal');

  function openDetail(b) {
    currentDetailId = b.id;
    const f = b.flight || {};
    const pay = b.payment || {};

    $('#bookingModalTitle').textContent = `Booking ${b.pnr || b.id}`;
    $('#dBkgId').textContent = b.id;
    $('#dPnr').textContent = b.pnr || '—';
    $('#dTicketNo').textContent = b.ticketNo || '—';
    $('#dStatus').innerHTML = `<span class="badge ${bookingBadge(b.status)}">${escapeHtml(b.status)}</span>`;
    $('#dCreated').textContent = fmtDateTime(b.createdAt);

    $('#dFlightNo').textContent = f.flightNo || '—';
    $('#dAirline').textContent = (f.airline && f.airline.name) || '—';
    $('#dRoute').textContent = f.from && f.to ? `${f.from} → ${f.to}` : '—';
    $('#dDate').textContent = f.date ? fmtDate(f.date) : '—';
    $('#dTimes').textContent = f.depart ? `${to12(f.depart)} → ${to12(f.arrive)}` : '—';
    $('#dClass').textContent = f.flightClass || '—';

    $('#dName').textContent = b.customer || '—';
    $('#dPhone').textContent = b.phone || '—';
    $('#dEmail').textContent = b.email || '—';

    $('#dMethod').textContent = pay.method || '—';
    $('#dTxn').textContent = pay.txnId || '—';
    $('#dPaidAt').textContent = pay.paidAt ? fmtDateTime(pay.paidAt) : '—';
    $('#dFare').textContent = f.pricePerPassenger ? `${fmtNPR(f.pricePerPassenger)} / pax` : '—';
    $('#dTotal').textContent = fmtNPR(b.amount);

    const pax = Array.isArray(b.passengers) ? b.passengers : [];
    $('#dPaxBody').innerHTML = pax.length
      ? pax
          .map(
            (p, i) => `
        <tr>
          <td>${i + 1}</td>
          <td>${escapeHtml(p.title || '—')}</td>
          <td><strong>${escapeHtml(`${p.firstName || ''} ${p.lastName || ''}`.trim() || p.name || '—')}</strong></td>
          <td><span class="badge badge-neutral">${escapeHtml(p.type || 'ADT')}</span></td>
          <td>${escapeHtml(p.nationality || '—')}</td>
        </tr>`
          )
          .join('')
      : '<tr><td colspan="5" style="text-align:center; color: var(--slate-light);">No passenger list stored for this booking.</td></tr>';

    $('#dCancelBtn').hidden = b.status !== 'Confirmed';
    detailModal.hidden = false;
  }

  function closeDetail() {
    detailModal.hidden = true;
    currentDetailId = null;
  }

  $('#bookingModalClose').addEventListener('click', closeDetail);
  $('#bookingModalDone').addEventListener('click', closeDetail);
  detailModal.addEventListener('click', (e) => {
    if (e.target === detailModal) closeDetail();
  });

  $('#dCancelBtn').addEventListener('click', () => {
    const b = bookings.find((x) => x.id === currentDetailId);
    if (b) {
      closeDetail();
      cancelBooking(b);
    }
  });

  /* ---------- Row action wiring (event delegation) ---------- */
  $('#bookingTableBody').addEventListener('click', (e) => {
    const viewBtn = e.target.closest('[data-view]');
    const cancelBtn = e.target.closest('[data-cancel]');
    const deleteBtn = e.target.closest('[data-delete]');

    if (viewBtn) {
      const b = bookings.find((x) => x.id === viewBtn.dataset.view);
      if (b) openDetail(b);
    }
    if (cancelBtn) {
      const b = bookings.find((x) => x.id === cancelBtn.dataset.cancel);
      if (b) cancelBooking(b);
    }
    if (deleteBtn) {
      const b = bookings.find((x) => x.id === deleteBtn.dataset.delete);
      if (b) deleteBooking(b);
    }
  });

  /* ---------- Escape closes the topmost modal ---------- */
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!confirmModal.hidden) { hideConfirm(); return; }
    if (!detailModal.hidden) closeDetail();
  });

  /* ---------- Utilities ---------- */
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* ---------- First render (API read — item 16) ---------- */
  apiGet('/api/admin/bookings')
    .then((resp) => {
      bookings = Array.isArray(resp.bookings) ? resp.bookings : [];
      bookings.forEach(normalizeRecord);
      render();
    })
    .catch(() => {
      bookings = loadBookings(); // mock fallback
      bookings.forEach(normalizeRecord);
      render();
    });
});
