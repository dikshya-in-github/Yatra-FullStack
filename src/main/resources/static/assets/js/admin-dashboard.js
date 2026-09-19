/* =========================================
   YATRA ADMIN — DASHBOARD JS
   Fills the §2.5 stat cards + the recent-
   bookings table from the API layer
   (item 16 — GET /api/admin/dashboard):
   - bookings (the recent-bookings table)
   - stats    (the seven §2.5 cards)

   Phase 13 moved every aggregate into
   GET /api/admin/dashboard: the endpoint
   answers the booking rows and a `stats`
   block that it computes with count/sum
   queries, and the mock route in api.js
   mirrors that shape (including the old
   seed-size fallbacks, which live there
   now). This file is a renderer only —
   no page-side arithmetic is left, so a
   card can never disagree with the API.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 5; // recent-bookings rows per page

  /* ---------- API reads (mock or real — pages never branch) ---------- */
  async function loadDashboard() {
    const resp = await apiGet('/api/admin/dashboard');
    return {
      bookings: Array.isArray(resp.bookings) ? resp.bookings : [],
      /* Phase 13 — the seven §2.5 numbers, computed by the endpoint (and by the
         mock, which mirrors it). Both modes answer this key. */
      stats: resp.stats || null
    };
  }

  /* ---------- Formatting helpers ---------- */
  function fmtNPR(n) {
    return 'NPR ' + Number(n || 0).toLocaleString('en-US', {
      minimumFractionDigits: 2, maximumFractionDigits: 2
    });
  }

  /* Compact revenue like the original dummy "NPR 42.6L" (lakh/crore) */
  function fmtShortNPR(n) {
    // A missing `stats` block handed this undefined, and every comparison below
    // failed through to Math.round(undefined) → "NPR NaN" on the card.
    n = Number(n) || 0;
    if (n >= 1e7) return 'NPR ' + (n / 1e7).toFixed(1) + 'Cr';
    if (n >= 1e5) return 'NPR ' + (n / 1e5).toFixed(1) + 'L';
    if (n >= 1e3) return 'NPR ' + (n / 1e3).toFixed(1) + 'K';
    return 'NPR ' + Math.round(n);
  }

  function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso.length > 10 ? iso : iso + 'T00:00:00');
    return isNaN(d) ? iso
      : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* ---------- Stat cards ---------- */
  /* Phase 13 — renderer only. The endpoint computes the seven numbers (and the
     mock mirrors it), so there is no page-side arithmetic left to disagree with
     the API and no seed-size fallback to hide a real 0. */
  function renderStats(data) {
    const stats = data.stats || {};

    if (!data.stats) {
      console.warn('Dashboard: the endpoint answered no `stats` block — showing zeros.');
    }

    $('#statUsers').textContent = stats.totalUsers || 0;
    $('#statFlights').textContent = stats.totalFlights || 0;
    $('#statAirlines').textContent = stats.totalAirlines || 0;
    $('#statBookings').textContent = stats.totalBookings || 0;
    $('#statToday').textContent = stats.todaysBookings || 0;
    $('#statRevenue').textContent = fmtShortNPR(stats.revenue);
    $('#statRevenue').title = 'Sum of confirmed (non-cancelled) bookings';
    $('#statPending').textContent = stats.pendingPayments || 0;
  }

  /* ---------- Recent bookings (live rows) ---------- */
  let page = 1;
  let lastData = null; // last dashboard payload — the page buttons re-render from it

  const totalPages = (rows) => Math.max(1, Math.ceil(rows.length / PAGE_SIZE));

  function renderRecent(data) {
    if (!data) return; // nothing loaded yet
    lastData = data;
    const bookings = data.bookings
      .slice()
      .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));

    page = Math.min(Math.max(1, page), totalPages(bookings));
    const start = (page - 1) * PAGE_SIZE;
    const pageRows = bookings.slice(start, start + PAGE_SIZE);

    const tbody = $('#recentBookingsBody');
    tbody.innerHTML = pageRows
      .map((b) => {
        const f = b.flight || {};
        return `
      <tr>
        <td><strong>${escapeHtml(b.pnr || '—')}</strong></td>
        <td>${escapeHtml(b.customer || '—')}</td>
        <td>${escapeHtml(f.flightNo || '—')}</td>
        <td>${f.from && f.to ? `${escapeHtml(f.from)} → ${escapeHtml(f.to)}` : '—'}</td>
        <td>${escapeHtml(fmtDate(f.date))}</td>
        <td><strong>${escapeHtml(fmtNPR(b.amount))}</strong></td>
        <td><span class="badge ${payBadgeClass(b.paymentStatus)}">${escapeHtml(b.paymentStatus || '—')}</span></td>
        <td><span class="badge ${bookingBadgeClass(b.status)}">${escapeHtml(b.status || '—')}</span></td>
      </tr>`;
      })
      .join('');

    $('#recentEmpty').hidden = bookings.length > 0;
    renderRecentPagination(bookings.length);
  }

  /* Same status→color semantics as admin-bookings.html */
  const bookingBadgeClass = (s) =>
    s === 'Confirmed' ? 'badge-success' : s === 'Cancelled' ? 'badge-danger' : 'badge-neutral';
  const payBadgeClass = (s) =>
    s === 'Paid' ? 'badge-success'
      : s === 'Pending' ? 'badge-warning'
        : s === 'Refunded' ? 'badge-info'
          : s === 'Failed' ? 'badge-danger' : 'badge-neutral';

  function renderRecentPagination(totalRows) {
    $('#recentPageInfo').textContent = totalRows
      ? `Showing ${Math.min((page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(page * PAGE_SIZE, totalRows)} of ${totalRows} booking${totalRows === 1 ? '' : 's'}`
      : 'No bookings';

    const btns = $('#recentPageBtns');
    btns.innerHTML = '';
    const pages = Math.max(1, Math.ceil(totalRows / PAGE_SIZE));
    if (pages <= 1) return;

    const mk = (label, target, opts = {}) => {
      const b = document.createElement('button');
      b.className = 'page-btn' + (opts.active ? ' active' : '');
      b.innerHTML = opts.icon ? `<i class="fa-solid ${opts.icon}"></i>` : label;
      b.setAttribute('aria-label', opts.label || `Page ${label}`);
      b.addEventListener('click', () => {
        page = target;
        renderRecent(lastData);
      });
      return b;
    };

    btns.appendChild(mk('', page - 1, { icon: 'fa-chevron-left', label: 'Previous page' }));
    for (let p = 1; p <= pages; p++) btns.appendChild(mk(p, p, { active: p === page }));
    btns.appendChild(mk('', page + 1, { icon: 'fa-chevron-right', label: 'Next page' }));
  }

  /* ---------- Page actions ---------- */
  const addFlightBtn = $('#dashAddFlight');
  if (addFlightBtn) {
    addFlightBtn.addEventListener('click', () => {
      window.location.href = './admin-flights.html';
    });
  }

  /* ---------- Export ----------
     The button used to be an `onclick="return false"` stub, so clicking it did
     nothing at all. It now downloads the rows the table is showing as a CSV the
     admin can open in Excel or Sheets. The rows come from `lastData` — the last
     dashboard read, which is the same array the table is drawn from — so the
     file can never disagree with what is on screen. */
  function csvCell(value) {
    const s = value == null || value === '' ? '' : String(value);
    // A field holding a comma, a quote or a line break has to be wrapped, with
    // its own quotes doubled (RFC 4180) — "Ghising, Dikshya" is one cell.
    return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  function exportBookings() {
    const rows = (lastData && lastData.bookings) || [];
    if (!rows.length) {
      showToast('There is nothing to export yet.', 'error');
      return;
    }

    const head = ['PNR', 'Customer', 'Flight', 'From', 'To', 'Date',
      'Amount (NPR)', 'Payment', 'Status', 'Booked on'];
    const lines = [head.map(csvCell).join(',')].concat(
      rows.map((b) => {
        const f = b.flight || {};
        return [b.pnr, b.customer, f.flightNo, f.from, f.to, f.date,
          b.amount, b.paymentStatus, b.status, b.createdAt].map(csvCell).join(',');
      })
    );

    // The BOM keeps Excel from mangling non-ASCII characters (names, NPR sign).
    const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'yatra-bookings-' + new Date().toISOString().slice(0, 10) + '.csv';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);

    showToast(`${rows.length} booking${rows.length === 1 ? '' : 's'} exported.`, 'success');
  }

  const exportBtn = $('#dashExport');
  if (exportBtn) exportBtn.addEventListener('click', exportBookings);

  /* ---------- First render ----------
     A failed read used to leave the markup's placeholder numbers (1,248 users,
     86 flights) on screen as if they were real, with the failure only in the
     console. The cards are zeroed and the failure is reported instead. */
  function showLoadFailure(err) {
    lastData = { bookings: [] };
    ['#statUsers', '#statFlights', '#statAirlines', '#statBookings', '#statToday', '#statPending']
      .forEach((sel) => { $(sel).textContent = '0'; });
    $('#statRevenue').textContent = fmtShortNPR(0);
    renderRecent(lastData);
    showToast('Could not load the dashboard: ' + ((err && err.message) || err), 'error');
  }

  loadDashboard()
    .then((data) => {
      renderStats(data);
      renderRecent(data);
    })
    .catch(showLoadFailure);
});
