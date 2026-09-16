/* =========================================
   MY BOOKINGS — customer booking history
   (checklist item 14). Data now comes through
   the mock API layer (item 16):
     apiGet("/api/users/me/bookings")
   which reads the SAME shared store the admin
   panel reads (yatra_bookings — written by
   esewaConfirm.js on PAY success and seeded
   by admin-bookings.js), so what the customer
   sees here is exactly what the admin sees in
   admin-bookings.html. Per-user scoping arrives
   with JWT auth (§3.3) — same endpoint then.

   Status buckets (derived, never stored):
   - Cancelled → booking.status === 'Cancelled'
   - Completed → departure date in the past
   - Upcoming  → departure date today or later

   View e-ticket: eticket.js renders from
   sessionStorage `yatra_transaction`, so the
   button loads the chosen booking into that
   key (shape kept identical: {ref, amount,
   method, txnId, paidAt, productAmount}) and
   navigates — works fully offline, and the
   same keys POST /api/bookings will use later.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 6;

  /* ---------- State ---------- */
  let state = { filter: 'all', page: 1 };
  let currentBkgId = null;

  /* Data access goes through api.js (item 16) — the same
     call serves mock mode now and the real Spring API
     later (GET /api/users/me/bookings, §36). */
  function loadBookings() {
    return apiGet('/api/users/me/bookings')
      .then((resp) => (resp && Array.isArray(resp.bookings)) ? resp.bookings : [])
      .catch(() => []); // demo page: an API failure renders the empty state
  }

  /* ---------- Helpers ---------- */
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

  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* Departure date may be plain YYYY-MM-DD */
  function departDate(b) {
    const f = b.flight || {};
    if (!f.date) return null;
    const d = new Date(f.date.length > 10 ? f.date : f.date + 'T00:00:00');
    return isNaN(d) ? null : d;
  }

  function bucketOf(b) {
    if (b.status === 'Cancelled') return 'Cancelled';
    const dep = departDate(b);
    if (dep && dep < new Date(new Date().toDateString())) return 'Completed'; // before today
    return 'Upcoming';
  }

  /* ---------- Stats ---------- */
  function renderStats(bookings) {
    const live = bookings.filter((b) => b.status !== 'Cancelled');
    const spent = live.reduce((sum, b) => sum + (Number(b.amount) || 0), 0);
    $('#statTotal').textContent = bookings.length;
    $('#statUpcoming').textContent = bookings.filter((b) => bucketOf(b) === 'Upcoming').length;
    $('#statSpent').textContent = fmtNPR(spent);
  }

  /* ---------- Filtering + pagination ---------- */
  function filtered(bookings) {
    if (state.filter === 'all') return bookings;
    return bookings.filter((b) => bucketOf(b) === state.filter);
  }

  /* ---------- Render (async — awaits the API layer) ---------- */
  function render() {
    return loadBookings().then((bookings) => {
      bookings = bookings
        .slice()
        .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));

      renderStats(bookings);

      const rows = filtered(bookings);
      const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
      state.page = Math.min(Math.max(1, state.page), pages);
      const start = (state.page - 1) * PAGE_SIZE;
      const pageRows = rows.slice(start, start + PAGE_SIZE);

      const list = $('#bookingList');
      list.innerHTML = pageRows
        .map((b) => {
          const f = b.flight || {};
          const bucket = bucketOf(b);
          const cancelled = bucket === 'Cancelled';
          const payCls = b.paymentStatus === 'Refunded' ? 'b-info'
            : b.paymentStatus === 'Paid' ? 'b-success' : 'b-neutral';
          return `
      <article class="booking-card${cancelled ? ' b-card-cancelled' : ''}">
        <div class="bc-pnr">
          <strong>${escapeHtml(b.pnr || '—')}</strong>
          <span class="cell-sub">${escapeHtml(b.id)} · booked ${escapeHtml(fmtDate(b.createdAt))}</span>
        </div>
        <div class="bc-route">
          <strong>${escapeHtml(f.from && f.to ? `${f.from} → ${f.to}` : '—')}</strong>
          <span class="cell-sub">${escapeHtml(f.flightNo || '')} · ${escapeHtml((f.airline && f.airline.name) || '')}</span>
          <span class="cell-sub">${escapeHtml(fmtDate(f.date))} · ${escapeHtml(to12(f.depart))}</span>
        </div>
        <div class="bc-amount">
          <strong>${fmtNPR(b.amount)}</strong>
          <span class="cell-sub">${escapeHtml((b.payment && b.payment.method) || 'eSewa')}</span>
        </div>
        <div class="bc-actions">
          <div class="bc-badges">
            <span class="bc-badge ${cancelled ? 'b-danger' : bucket === 'Upcoming' ? 'b-success' : 'b-neutral'}">${bucket}</span>
            <span class="bc-badge ${payCls}">${escapeHtml(b.paymentStatus || '—')}</span>
          </div>
          <button type="button" class="bc-btn solid" data-view="${escapeHtml(b.id)}"><i class="fa-solid fa-eye"></i> View</button>
        </div>
      </article>`;
        })
        .join('');

      const hasBookings = bookings.length > 0;
      $('#emptyBookings').hidden = rows.length > 0;
      $('#emptyMsg').textContent = !hasBookings
        ? 'No bookings here yet — your trips will appear here after checkout.'
        : `No ${state.filter === 'all' ? '' : state.filter.toLowerCase() + ' '}bookings right now.`;
      $('#resultCount').textContent = `${rows.length} booking${rows.length === 1 ? '' : 's'}`;

      renderPagination(rows.length, pages);
    });
  }

  function renderPagination(totalRows, pages) {
    const wrap = $('#listPagination');
    wrap.hidden = pages <= 1;
    $('#pageInfo').textContent = totalRows
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(state.page * PAGE_SIZE, totalRows)} of ${totalRows} bookings`
      : '';

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
    for (let p = 1; p <= pages; p++) btns.appendChild(mk(p, p, { active: p === state.page }));
    btns.appendChild(mk('', state.page + 1, { icon: 'fa-chevron-right', label: 'Next page' }));
  }

  /* ---------- Filter chips ---------- */
  $('#filterChips').addEventListener('click', (e) => {
    const chip = e.target.closest('.filter-chip');
    if (!chip) return;
    document.querySelectorAll('#filterChips .filter-chip').forEach((c) => c.classList.remove('active'));
    chip.classList.add('active');
    state.filter = chip.dataset.filter;
    state.page = 1;
    render();
  });

  /* ---------- Detail modal (mirrors the e-ticket data) ---------- */
  const modal = $('#bookingModal');

  function openDetail(b) {
    currentBkgId = b.id;
    const f = b.flight || {};
    const pay = b.payment || {};

    $('#bookingModalTitle').textContent = `Booking ${b.pnr || b.id}`;
    $('#dBkgId').textContent = b.id;
    $('#dPnr').textContent = b.pnr || '—';
    $('#dTicketNo').textContent = b.ticketNo || '—';
    $('#dStatus').innerHTML =
      `<span class="bc-badge ${b.status === 'Cancelled' ? 'b-danger' : 'b-success'}">${escapeHtml(b.status)}</span>`;
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
          .map((p, i) => `
        <tr>
          <td>${i + 1}</td>
          <td>${escapeHtml(p.title || '—')}</td>
          <td><strong>${escapeHtml(`${p.firstName || ''} ${p.lastName || ''}`.trim() || p.name || '—')}</strong></td>
          <td><span class="bc-badge b-neutral">${escapeHtml(p.type || 'ADT')}</span></td>
          <td>${escapeHtml(p.nationality || '—')}</td>
        </tr>`)
          .join('')
      : '<tr><td colspan="5" style="text-align:center; color: var(--slate-light);">No passenger list stored for this booking.</td></tr>';

    modal.hidden = false;
  }

  function closeDetail() {
    modal.hidden = true;
    currentBkgId = null;
  }

  $('#bookingModalClose').addEventListener('click', closeDetail);
  $('#bookingModalDone').addEventListener('click', closeDetail);
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeDetail();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !modal.hidden) closeDetail();
  });

  /* ---------- View e-ticket: load the booking into yatra_transaction ---------- */
  $('#dEticketBtn').addEventListener('click', () => {
    loadBookings().then((bookings) => {
      const b = bookings.find((x) => x.id === currentBkgId);
      if (!b) return;
      const pay = b.payment || {};
      // Same shape eticket.js reads: txn.ref = flight, plus payment fields.
      sessionStorage.setItem('yatra_transaction', JSON.stringify({
        ref: b.flight || {},
        amount: b.amount,
        productAmount: b.productAmount != null ? b.productAmount : b.amount,
        method: pay.method || 'eSewa',
        txnId: pay.txnId || '',
        paidAt: pay.paidAt || b.createdAt || ''
      }));
      window.location.href = './eticket.html';
    });
  });

  /* ---------- Card action wiring (event delegation) ---------- */
  $('#bookingList').addEventListener('click', (e) => {
    const viewBtn = e.target.closest('[data-view]');
    if (viewBtn) {
      loadBookings().then((bookings) => {
        const b = bookings.find((x) => x.id === viewBtn.dataset.view);
        if (b) openDetail(b);
      });
    }
  });

  /* ---------- First render ---------- */
  render();
});
