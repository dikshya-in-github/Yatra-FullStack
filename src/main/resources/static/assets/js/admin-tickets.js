/* =========================================
   YATRA ADMIN — TICKETS PAGE JS
   e-ticket search/view (Master Plan §2.1 #9). Fully wired to the backend as of
   the fix-plan §12/§13 pass — the same migration admin-flights.js,
   admin-airlines.js, admin-destinations.js, admin-bookings.js, admin-users.js
   and admin-payments.js went through. It is the last of the seven.

   Reads  : GET /api/admin/tickets              (search + status + ticketStatus + paging, server-side)
            GET /api/admin/tickets/{bookingId}  (the detail modal's FRESH copy)

   There is no write at all, and that is the module's shape rather than a gap
   left over: the API serves no ticket create, void or delete — a document is
   issued by the checkout when a payment settles (`TicketService.issue`, called
   from `PaymentService.settle`), and cancelling a booking starts on
   admin-bookings.html. So every call here goes through api.js's read path, this
   file contains no localStorage access, and it owns no store.

   It used to own one: `yatra_bookings`, the storefront's key, with
   `readBookings()`/`filtered()`/client-side paging, and `GET /api/admin/tickets`
   behind a `.catch()` that silently fell back to those seeds. Two consequences
   were visible on screen, and both are fixed here:

   - **The Status column told the wrong story.** It derived "Issued"/"Voided"
     from the BOOKING's status (`b.status === 'Cancelled'`) and never read the
     ticket row's own `ISSUED`/`CANCELLED` column, which the API exposes for this
     page as `ticketStatus`. The two are independent states rather than two
     spellings of one — **nothing in the API voids a document**: `issue` writes
     ISSUED when a payment settles, `refund` moves money and touches nothing
     else, and cancelling a booking leaves the ticket alone. So a cancelled
     booking's still-live document was painted "Voided".
     (The demo's only CANCELLED ticket is the seeder's, on a booking whose status
     the seeder also writes as cancelled — which is why the derived label looked
     right everywhere until a real cancellation went through.)
     The badge now reads the document's own column, the booking's state sits
     beside it as a sub-line, and the toolbar filters on either.
   - **The search box promised passenger search the endpoint did not do.** The
     query matched PNR, ticket number, booking id, contact fields and flight
     number — not the passenger names this page prints in its own Passenger
     column. `TicketRepository` now matches those too (a correlated `EXISTS`, so
     paging stays a database page rather than an in-memory slice), which is the
     promise the placeholder has always made.

   Rules baked in:   - **The two states sit side by side, because they are different columns.** The
     badge is the document's own state; the sub-line under it is the booking's. A
     cancelled booking's ticket is still ISSUED (no API call voids a document), so
     a page that showed only one of the two would look wrong to whoever filtered
     for the other.
   - **The detail modal is a FRESH read** of `GET /api/admin/tickets/{bookingId}`
     — an endpoint built for this page in Phase 12 and never called until now
     (§12/§13 found the same shape of gap on Users and Payments). A ticket voided
     since the list was drawn is visible without reloading.
   - **Both filters are queries, and they are different columns.** `status` is the
     booking's lifecycle (`Confirmed`/`Cancelled`), `ticketStatus` is the
     document's own state (`ISSUED`/`CANCELLED`, shown as Issued/Voided). The
     page's filter values are the storage spellings the endpoint case-folds,
     because "Voided" is not what the column holds.
   - Reads carry a sequence number, so a slow answer for an older query cannot
     redraw the table.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 8;

  /* ---------- State ----------
     `tickets` is ONE PAGE of the server's answer — never the whole table. */
  let tickets = [];
  let state = {
    search: '', bookingStatus: 'ALL', ticketStatus: 'ALL',
    page: 1, totalPages: 1, totalElements: 0
  };
  let readSeq = 0;

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page. */

  /* ---------- Query, not filtering ----------
     `page` is 1-based here and 0-based on the wire. The two filters speak
     different vocabularies on purpose: the booking status is the page's
     title-case one (what the API answers and what the badges compare against),
     while the ticket status is the column's own uppercase ISSUED/CANCELLED —
     the endpoint folds case, but it cannot guess that "Voided" means CANCELLED. */
  function listQuery() {
    const p = new URLSearchParams();
    if (state.search.trim()) p.set('search', state.search.trim());
    if (state.bookingStatus !== 'ALL') p.set('status', state.bookingStatus);
    if (state.ticketStatus !== 'ALL') p.set('ticketStatus', state.ticketStatus);
    p.set('page', String(Math.max(0, state.page - 1)));
    p.set('size', String(PAGE_SIZE));
    return '/api/admin/tickets?' + p.toString();
  }

  /* ---------- Formatting ---------- */
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
      : d.toLocaleString('en-GB', {
        day: 'numeric', month: 'short', year: 'numeric',
        hour: '2-digit', minute: '2-digit'
      });
  }

  function to12(hhmm) {
    if (!hhmm) return '—';
    const p = hhmm.split(':'), h = +p[0];
    return ((h + 11) % 12 + 1) + ':' + p[1] + ' ' + (h >= 12 ? 'PM' : 'AM');
  }

  /* Same status→color semantics as the other admin pages. The ticket badge reads
     the DOCUMENT's own state, not the booking's — see the file header. */
  const ticketBadge = (s) => (s === 'CANCELLED' ? 'badge-danger' : 'badge-success');
  const ticketWord = (s) => (s === 'CANCELLED' ? 'Voided' : 'Issued');
  const payBadge = (s) =>
    s === 'Paid' ? 'badge-success'
      : s === 'Pending' ? 'badge-warning'
        : s === 'Refunded' ? 'badge-info'
          : s === 'Failed' ? 'badge-danger' : 'badge-neutral';

  /* ---------- Table render ---------- */
  function render() {
    const tbody = $('#ticketTableBody');
    tbody.innerHTML = tickets
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
        <td><span class="badge ${ticketBadge(b.ticketStatus)}">${escapeHtml(ticketWord(b.ticketStatus))}</span>
          <span class="cell-sub">booking: ${escapeHtml(b.status || '—')}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-view="${escapeHtml(b.id)}" aria-label="View ticket ${escapeHtml(b.ticketNo || b.pnr || b.id)}"><i class="fa-solid fa-eye"></i></button>
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = tickets.length > 0;
    $('#resultCount').textContent =
      `${state.totalElements} ticket${state.totalElements === 1 ? '' : 's'}`;
    renderPagination();
  }

  function renderPagination() {
    const pages = Math.max(1, state.totalPages);
    const total = state.totalElements;

    $('#pageInfo').textContent = total
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, total)}–${Math.min(state.page * PAGE_SIZE, total)} of ${total} tickets`
      : 'No tickets';

    const btns = $('#pageBtns');
    btns.innerHTML = '';
    if (pages <= 1) return;

    const mk = (label, page, opts = {}) => {
      const b = document.createElement('button');
      b.className = 'page-btn' + (opts.active ? ' active' : '');
      b.innerHTML = opts.icon ? `<i class="fa-solid ${opts.icon}"></i>` : label;
      b.setAttribute('aria-label', opts.label || `Page ${label}`);
      b.addEventListener('click', () => goToPage(page));
      return b;
    };

    btns.appendChild(mk('', state.page - 1, { icon: 'fa-chevron-left', label: 'Previous page' }));
    for (let p = 1; p <= pages; p++) {
      btns.appendChild(mk(p, p, { active: p === state.page }));
    }
    btns.appendChild(mk('', state.page + 1, { icon: 'fa-chevron-right', label: 'Next page' }));
  }

  /* ---------- Toolbar events ----------
     Each one re-queries the backend and resets to page 1. Typing is debounced so
     a search fires one request, not one per keystroke. */
  let searchTimer;
  $('#ticketSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(reloadOrToast, 250);
  });

  $('#bookingStatusFilter').addEventListener('change', (e) => {
    state.bookingStatus = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  $('#ticketStatusFilter').addEventListener('change', (e) => {
    state.ticketStatus = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  /* ---------- Reads: one path, used on load, on every filter/page change ---------- */
  async function reload() {
    const seq = ++readSeq;
    const resp = await apiGet(listQuery());
    if (seq !== readSeq) return; // superseded: a newer read owns the table

    tickets = Array.isArray(resp.bookings) ? resp.bookings : [];

    const total = Number(resp.totalElements);
    state.totalElements = Number.isFinite(total) ? total : tickets.length;
    const pages = Number(resp.totalPages);
    state.totalPages = Number.isFinite(pages) && pages > 0
      ? pages
      : Math.max(1, Math.ceil(state.totalElements / PAGE_SIZE));

    /* A filtered list can leave the view past the end of the result set (asking
       for page 3 of 2). The server answers that honestly — an empty page with
       the real counts — so step back once rather than rendering "No tickets"
       over a table that has some. */
    if (!tickets.length && state.page > 1 && state.page > state.totalPages) {
      state.page = state.totalPages;
      return reload();
    }

    render();
  }

  function showLoadFailure(err) {
    tickets = [];
    state.totalElements = 0;
    state.totalPages = 1;
    render();
    showToast('Could not load tickets: ' + ((err && err.message) || err), 'error');
  }

  function reloadOrToast() {
    return reload().catch((err) => {
      showToast('Could not load tickets: ' + ((err && err.message) || err), 'error');
    });
  }

  function goToPage(page) {
    const last = Math.max(1, state.totalPages);
    state.page = Math.min(Math.max(1, page), last);
    reloadOrToast();
  }

  /* ---------- Ticket detail modal ----------
     A FRESH read of the document, not the cached list row: `GET
     /api/admin/tickets/{bookingId}` was built for this page and had no caller
     until now, so a ticket voided since the list was drawn was invisible. The
     endpoint answers the same row shape the list does. */
  const detailModal = $('#ticketModal');

  function openDetail(id, button) {
    if (button) button.disabled = true;
    apiGet('/api/admin/tickets/' + id)
      .then((b) => {
        const f = b.flight || {};
        const pay = b.payment || {};
        const pax = Array.isArray(b.passengers) ? b.passengers : [];

        $('#ticketModalTitle').textContent = `Ticket ${b.ticketNo || b.pnr || b.id}`;
        $('#dTicketNo').textContent = b.ticketNo || '—';
        $('#dPnr').textContent = b.pnr || '—';
        $('#dBkgId').textContent = b.id;
        $('#dIssuedAt').textContent = fmtDateTime(b.createdAt);
        $('#dTicketStatus').innerHTML =
          `<span class="badge ${ticketBadge(b.ticketStatus)}">${escapeHtml(ticketWord(b.ticketStatus))}</span>`;

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
        $('#dPayStatus').innerHTML =
          `<span class="badge ${payBadge(b.paymentStatus)}">${escapeHtml(b.paymentStatus || '—')}</span>`;
        $('#dFare').textContent = f.pricePerPassenger
          ? `${fmtNPR(f.pricePerPassenger)} / pax` : '—';
        $('#dTotal').textContent = fmtNPR(b.amount);

        $('#dPaxBody').innerHTML = pax.length
          ? pax.map((p, i) => `
        <tr>
          <td>${i + 1}</td>
          <td>${escapeHtml(p.title || '—')}</td>
          <td><strong>${escapeHtml(`${p.firstName || ''} ${p.lastName || ''}`.trim() || '—')}</strong></td>
          <td><span class="badge badge-neutral">${escapeHtml(p.type || 'ADT')}</span></td>
          <td>${escapeHtml(p.nationality || '—')}</td>
        </tr>`).join('')
          : '<tr><td colspan="5" style="text-align:center; color: var(--slate-light);">No passenger list stored for this booking.</td></tr>';

        detailModal.hidden = false;
      })
      .catch((err) => {
        showToast('Could not open that ticket: ' + ((err && err.message) || err), 'error');
      })
      .finally(() => {
        if (button) button.disabled = false;
      });
  }

  function closeDetail() {
    detailModal.hidden = true;
  }

  $('#ticketModalClose').addEventListener('click', closeDetail);
  $('#ticketModalDone').addEventListener('click', closeDetail);
  detailModal.addEventListener('click', (e) => {
    if (e.target === detailModal) closeDetail();
  });

  /* ---------- Row action wiring (event delegation) ---------- */
  $('#ticketTableBody').addEventListener('click', (e) => {
    const viewBtn = e.target.closest('[data-view]');
    if (viewBtn) openDetail(viewBtn.dataset.view, viewBtn);
  });

  /* ---------- Escape closes the modal ---------- */
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !detailModal.hidden) closeDetail();
  });

  /* ---------- Utilities ---------- */
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* ---------- First render ----------
     No seed fallback: the documents are the database's, and if they cannot be
     read the page says so rather than drawing tickets MySQL has never issued. */
  reload().catch(showLoadFailure);
});
