/* =========================================
   YATRA ADMIN — TICKETS PAGE JS
   e-ticket search/view (Master Plan §2.1 #9).
   Like admin-payments.js, there is no separate
   store: tickets are DERIVED from
   `yatra_bookings` — each booking's pnr +
   ticketNo is the document eticket.html
   printed, and `passengers` is the ticket set.

   READ-ONLY by design (Master Plan Part 5:
   admin "views" e-tickets — issuing/reissuing/
   voiding belongs to the backend's
   TicketService). Cancellation starts at
   admin-bookings.html and is reflected here
   automatically since both read the same
   store. When the Spring API lands, the store
   read swaps for apiGet('/api/tickets').
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const BOOKING_KEY = 'yatra_bookings'; // owned by the storefront (esewaConfirm.js)
  const PAGE_SIZE = 8;

  /* ---------- State (mock "repository") ---------- */
  let bookings = []; // initial read goes through the API layer (item 16)
  let state = { search: '', status: 'ALL', page: 1 };

  function readBookings() {
    try {
      const raw = localStorage.getItem(BOOKING_KEY);
      const data = raw ? JSON.parse(raw) : [];
      return Array.isArray(data) ? data : [];
    } catch (err) {
      return [];
    }
  }

  /* ---------- Filtering + pagination ---------- */
  function tickets() {
    return bookings.filter((b) => b.pnr || b.ticketNo); // a booking with a document
  }

  function filtered() {
    const q = state.search.trim().toLowerCase();
    return tickets().filter((b) => {
      const f = b.flight || {};
      const pax = Array.isArray(b.passengers) ? b.passengers : [];
      const paxNames = pax
        .map((p) => `${p.firstName || ''} ${p.lastName || ''}`.trim())
        .join(' ')
        .toLowerCase();
      const matchesQ =
        !q ||
        (b.pnr || '').toLowerCase().includes(q) ||
        (b.ticketNo || '').toLowerCase().includes(q) ||
        (b.customer || '').toLowerCase().includes(q) ||
        (f.flightNo || '').toLowerCase().includes(q) ||
        b.id.toLowerCase().includes(q) ||
        paxNames.includes(q);
      const matchesStatus = state.status === 'ALL' || b.status === state.status;
      return matchesQ && matchesStatus;
    });
  }

  const totalPages = () => Math.max(1, Math.ceil(filtered().length / PAGE_SIZE));

  function clampPage() {
    state.page = Math.min(Math.max(1, state.page), totalPages());
  }

  /* ---------- Formatting helpers ---------- */
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

  /* Same status→color semantics as the other admin pages */
  const ticketBadge = (s) => (s === 'Confirmed' ? 'badge-success' : 'badge-danger');
  const payBadge = (s) =>
    s === 'Paid' ? 'badge-success'
      : s === 'Pending' ? 'badge-warning'
        : s === 'Refunded' ? 'badge-info'
          : s === 'Failed' ? 'badge-danger' : 'badge-neutral';

  /* ---------- Table render ---------- */
  function render() {
    clampPage();
    const rows = filtered();
    const start = (state.page - 1) * PAGE_SIZE;
    const pageRows = rows.slice(start, start + PAGE_SIZE);

    const tbody = $('#ticketTableBody');
    tbody.innerHTML = pageRows
      .map((b) => {
        const f = b.flight || {};
        const pax = Array.isArray(b.passengers) ? b.passengers : [];
        const lead = pax[0] ? `${pax[0].firstName || ''} ${pax[0].lastName || ''}`.trim() : '';
        const paxCount = f.passengerCount || pax.length || 1;
        return `
      <tr>
        <td><strong>${escapeHtml(b.ticketNo || '—')}</strong>
          <span class="cell-sub">PNR ${escapeHtml(b.pnr || '—')}</span></td>
        <td>
          <strong>${escapeHtml(lead || b.customer || '—')}</strong>
          <span class="cell-sub">${paxCount > 1 ? `+${paxCount - 1} more on this set` : 'solo traveller'}</span>
        </td>
        <td><strong>${escapeHtml(f.flightNo || '—')}</strong>
          <span class="cell-sub">${escapeHtml((f.airline && f.airline.name) || '')}</span></td>
        <td>
          <strong>${escapeHtml(f.from && f.to ? `${f.from} → ${f.to}` : '—')}</strong>
          <span class="cell-sub">${escapeHtml(fmtDate(f.date))} · ${escapeHtml(to12(f.depart))}</span>
        </td>
        <td>${escapeHtml(b.pnr || '—')}
          <span class="cell-sub">${escapeHtml(b.id)}</span></td>
        <td><span class="badge ${payBadge(b.paymentStatus)}">${escapeHtml(b.paymentStatus || '—')}</span></td>
        <td><strong>${fmtNPR(b.amount)}</strong></td>
        <td><span class="badge ${ticketBadge(b.status)}">${escapeHtml(b.status === 'Cancelled' ? 'Voided' : 'Issued')}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-view="${escapeHtml(b.id)}" aria-label="View ticket ${escapeHtml(b.ticketNo || b.pnr || b.id)}"><i class="fa-solid fa-eye"></i></button>
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = rows.length > 0;
    $('#resultCount').textContent = `${rows.length} ticket${rows.length === 1 ? '' : 's'}`;
    renderPagination(rows.length);
  }

  function renderPagination(totalRows) {
    const pages = totalPages();
    $('#pageInfo').textContent = totalRows
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(state.page * PAGE_SIZE, totalRows)} of ${totalRows} tickets`
      : 'No tickets';

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
  $('#ticketSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    render();
  });

  $('#ticketStatusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
    state.page = 1;
    render();
  });

  /* ---------- Ticket detail modal (read-only; mirrors eticket.js data) ---------- */
  const detailModal = $('#ticketModal');

  function openDetail(b) {
    const f = b.flight || {};
    const pay = b.payment || {};

    $('#ticketModalTitle').textContent = `Ticket ${b.ticketNo || b.pnr || b.id}`;
    $('#dTicketNo').textContent = b.ticketNo || '—';
    $('#dPnr').textContent = b.pnr || '—';
    $('#dBkgId').textContent = b.id;
    $('#dIssuedAt').textContent = fmtDateTime(b.createdAt);
    $('#dTicketStatus').innerHTML =
      `<span class="badge ${ticketBadge(b.status)}">${escapeHtml(b.status === 'Cancelled' ? 'Voided' : 'Issued')}</span>`;

    $('#dFlightNo').textContent = f.flightNo || '—';
    $('#dAirline').textContent = (f.airline && f.airline.name) || '—';
    $('#dRoute').textContent = f.from && f.to ? `${f.from} → ${f.to}` : '—';
    $('#dDate').textContent = f.date ? fmtDate(f.date) : '—';
    $('#dTimes').textContent = f.depart ? `${to12(f.depart)} → ${to12(f.arrive)}` : '—';
    $('#dClass').textContent = f.flightClass || '—';

    $('#dCustName').textContent = b.customer || '—';
    $('#dCustPhone').textContent = b.phone || '—';
    $('#dCustEmail').textContent = b.email || '—';

    $('#dMethod').textContent = pay.method || '—';
    $('#dTxn').textContent = pay.txnId || '—';
    $('#dPayStatus').innerHTML = `<span class="badge ${payBadge(b.paymentStatus)}">${escapeHtml(b.paymentStatus || '—')}</span>`;
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
          <td><span class="badge badge-neutral">${escapeHtml(p.type || 'ADT')}</span></td>
          <td>${escapeHtml(p.nationality || '—')}</td>
        </tr>`)
          .join('')
      : '<tr><td colspan="5" style="text-align:center; color: var(--slate-light);">No passenger list stored for this booking.</td></tr>';

    detailModal.hidden = false;
  }

  function closeDetail() {
    detailModal.hidden = true;
  }

  $('#ticketModalClose').addEventListener('click', closeDetail);
  $('#ticketModalDone').addEventListener('click', closeDetail);
  detailModal.addEventListener('click', (e) => {
    if (e.target === detailModal) closeDetail();
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !detailModal.hidden) closeDetail();
  });

  /* ---------- Row action wiring (event delegation) ---------- */
  $('#ticketTableBody').addEventListener('click', (e) => {
    const viewBtn = e.target.closest('[data-view]');
    if (viewBtn) {
      const b = bookings.find((x) => x.id === viewBtn.dataset.view);
      if (b) openDetail(b);
    }
  });

  /* ---------- First render (API read — item 16) ---------- */
  apiGet('/api/admin/tickets')
    .then((resp) => {
      bookings = Array.isArray(resp.bookings) ? resp.bookings : [];
      render();
    })
    .catch(() => {
      bookings = readBookings(); // mock fallback
      render();
    });
});
