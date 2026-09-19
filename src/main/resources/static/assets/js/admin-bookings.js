/* =========================================
   YATRA ADMIN — BOOKINGS PAGE JS
   Read-and-manage view of customer bookings (Master Plan §2.1 #5).
   Fully wired to the backend as of the fix-plan §12/§13 pass —
   the same migration `admin-flights.js`, `admin-airlines.js` and
   `admin-destinations.js` went through.

   Reads  : GET /api/admin/bookings        (search + status + paymentStatus + paging, server-side)
            GET /api/admin/bookings/{id}   (the detail modal's FRESH copy)
   Writes : PUT  /api/admin/bookings/{id}/status   (cancel, and confirm)
            POST /api/admin/reset + /api/admin/seed (the demo reset)

   All of them through api.js, so this file contains no localStorage
   access and no seed rows of its own. It used to own both
   (`yatra_bookings`, `yatra_bookings_seeded_v1`, `SEED_BOOKINGS`,
   `loadBookings()`/`saveBookings()`, `freeSeatsFor()`), which meant
   three of its four actions wrote to the browser only: cancelling a
   booking changed a JSON array in localStorage while the row in MySQL
   stayed Confirmed.

   Rules baked in, and where they come from:
   - **A booking is not created here and not deleted here.** The
     create path is the customer wizard (§7), and there is deliberately
     no booking DELETE endpoint anywhere in the API: R4's rule is that a
     booking with a payment or a ticket is never hard-deleted, so the
     trash icon that only appeared on the mock's own `_seed` rows is
     gone. **Cancelling is this module's disable**, and it is soft — the
     seats stay counted on the flight, which is what the page subtitle
     has always said.
   - **Confirm is here too.** `PUT /{id}/status` accepts it, and the
     service's rule is the interesting part: a booking can only become
     Confirmed when a SUCCESS payment row exists, so the admin's Confirm
     is a real workflow step whose refusal (409
     INVALID_STATUS_TRANSITION) arrives in the toast.
   - **The detail modal is a fresh read.** It used to render whichever
     fields the list row happened to carry; it now fetches
     `GET /api/admin/bookings/{id}`, so a payment taken since the list
     was drawn is visible without reloading the page.
   - **Reads are QUERIES.** All three toolbar controls (`search`,
     booking status, payment status) and the page number travel to
     MySQL, and the count line and the pagination come back from it.
     The status filter gained a **Pending** option: the API emits it, the
     badges already colour it, and the page previously had no way to
     filter to it.
   - **Reset demo data is a real admin operation now**
     (`POST /api/admin/reset` then `/seed`). It removes only the rows the
     seeder created and keeps records the admin made, which is the
     button's own promise — but it is a whole-demo reset, not a
     bookings-only one, so its confirmation says so.
   - After any write the list is re-read, so the table can never show a
     status the database does not hold.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 8;

  /* ---------- State ----------
     `bookings` is ONE PAGE of the server's answer — never the whole
     table. `totalElements`/`totalPages` are the server's counts from the
     last list call, so nothing here recomputes a total from the rows on
     screen. */
  let bookings = [];
  let state = {
    search: '', bookingStatus: 'ALL', payStatus: 'ALL',
    page: 1, totalPages: 1, totalElements: 0
  };
  let confirmAction = null;   // queue for the styled confirm modal
  let currentDetailId = null; // the row the detail modal is showing
  /* Every read carries a sequence number (see reload()). The walk caught this page
     drawing the "status = ALL" answer over a "payment status = Paid" query: two
     filters changed in quick succession are two reads in flight, and HTTP does not
     promise they come back in the order they left. */
  let readSeq = 0;

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page,
     which also creates the #toast element it writes to. */

  /* ---------- Query, not filtering ----------
     `page` is 1-based in this UI and 0-based on the wire (Spring's convention).
     Both filter vocabularies are the page's own (`ALL` means "send nothing"),
     and the status values are title-case because that is what the API answers
     back and what the badges and `canCancel` compare against (R16). */
  function listQuery() {
    const p = new URLSearchParams();
    if (state.search.trim()) p.set('search', state.search.trim());
    if (state.bookingStatus !== 'ALL') p.set('status', state.bookingStatus);
    if (state.payStatus !== 'ALL') p.set('paymentStatus', state.payStatus);
    p.set('page', String(Math.max(0, state.page - 1)));
    p.set('size', String(PAGE_SIZE));
    return '/api/admin/bookings?' + p.toString();
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

  const bookingBadge = (s) => (s === 'Confirmed' ? 'badge-success' : 'badge-danger');
  const payBadge = (s) => (s === 'Paid' ? 'badge-success' : 'badge-info');

  /* ---------- Table render ---------- */
  function render() {
    const tbody = $('#bookingTableBody');
    tbody.innerHTML = bookings
      .map((b) => {
        const f = b.flight || {};
        const pax = f.passengerCount || (Array.isArray(b.passengers) ? b.passengers.length : 1);
        const canConfirm = b.status === 'Pending';
        const canCancel = b.status === 'Confirmed';
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
        <td><span class="badge ${payBadge(b.paymentStatus)}">${escapeHtml(b.paymentStatus || '—')}</span>
          <span class="cell-sub">${escapeHtml((b.payment && b.payment.method) || '')}</span></td>
        <td><span class="badge ${bookingBadge(b.status)}">${escapeHtml(b.status || '—')}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-view="${b.id}" aria-label="View ${escapeHtml(b.pnr || b.id)}"><i class="fa-solid fa-eye"></i></button>
            ${canConfirm ? `<button type="button" class="icon-btn" data-confirm="${b.id}" aria-label="Confirm booking ${escapeHtml(b.pnr || b.id)}"><i class="fa-solid fa-circle-check"></i></button>` : ''}
            ${canCancel ? `<button type="button" class="icon-btn" data-cancel="${b.id}" aria-label="Cancel booking ${escapeHtml(b.pnr || b.id)}"><i class="fa-solid fa-ban"></i></button>` : ''}
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = bookings.length > 0;
    $('#resultCount').textContent =
      `${state.totalElements} booking${state.totalElements === 1 ? '' : 's'}`;
    renderPagination();
  }

  function renderPagination() {
    const pages = Math.max(1, state.totalPages);
    const total = state.totalElements;

    $('#pageInfo').textContent = total
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, total)}–${Math.min(state.page * PAGE_SIZE, total)} of ${total} bookings`
      : 'No bookings';

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
     Each one re-queries the backend and resets to page 1. Typing is debounced
     so a search fires one request, not one per keystroke. */
  let searchTimer;
  $('#bookingSearch').addEventListener('input', (e) => {
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

  $('#payStatusFilter').addEventListener('change', (e) => {
    state.payStatus = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  /* ---------- Reads: one path, used on load, on every filter/page change and
     after every write ---------- */
  async function reload() {
    const seq = ++readSeq;
    const resp = await apiGet(listQuery());
    if (seq !== readSeq) return; // superseded: a newer read owns the table

    bookings = Array.isArray(resp.bookings) ? resp.bookings : [];

    const total = Number(resp.totalElements);
    state.totalElements = Number.isFinite(total) ? total : bookings.length;
    const pages = Number(resp.totalPages);
    state.totalPages = Number.isFinite(pages) && pages > 0
      ? pages
      : Math.max(1, Math.ceil(state.totalElements / PAGE_SIZE));

    /* A filtered list can leave the view past the end of the result set
       (asking for page 3 of 2). The server answers that honestly — an empty
       page with the real counts — so step back once rather than rendering
       "No bookings" over a table that has some. */
    if (!bookings.length && state.page > 1 && state.page > state.totalPages) {
      state.page = state.totalPages;
      return reload();
    }

    render();
  }

  function showLoadFailure(err) {
    bookings = [];
    state.totalElements = 0;
    state.totalPages = 1;
    render();
    showToast('Could not load bookings: ' + ((err && err.message) || err), 'error');
  }

  function reloadOrToast() {
    return reload().catch((err) => {
      showToast('Could not load bookings: ' + ((err && err.message) || err), 'error');
    });
  }

  function goToPage(page) {
    const last = Math.max(1, state.totalPages);
    state.page = Math.min(Math.max(1, page), last);
    reloadOrToast();
  }

  /* ---------- Styled confirm modal ---------- */
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

  /* ---------- The status write ----------
     One function for both directions: cancel and confirm are the same PUT with
     a different value, which is how the API models them. The server's own
     sentence is what a refusal shows — a confirm without a SUCCESS payment is
     409 INVALID_STATUS_TRANSITION, and no local rule here tries to predict it. */
  function setStatus(id, next, button) {
    if (button) button.disabled = true;
    return apiPut('/api/admin/bookings/' + id + '/status', { status: next })
      .then(() => {
        showToast(`Booking ${id} is now ${next.toLowerCase()}.`, 'success');
        return reload();
      })
      .catch((err) => {
        if (button) button.disabled = false;
        showToast((err && err.message) || 'The booking could not be updated.', 'error');
      });
  }

  function cancelBooking(b, button) {
    askConfirm({
      title: 'Cancel this booking?',
      message: `Booking ${b.pnr || b.id} for ${b.customer || 'this customer'} will be marked Cancelled. `
        + 'The seats stay counted on the flight — the refund itself is completed by the payments flow, not here.',
      yesLabel: 'Cancel booking',
      icon: 'ban',
      onYes: () => setStatus(b.id, 'Cancelled', button)
    });
  }

  function confirmBooking(b, button) {
    askConfirm({
      title: 'Confirm this booking?',
      message: `Booking ${b.pnr || b.id} for ${b.customer || 'this customer'} will be marked Confirmed. `
        + 'A booking can only be confirmed when it has a successful payment.',
      yesLabel: 'Confirm booking',
      icon: 'circle-check',
      onYes: () => setStatus(b.id, 'Confirmed', button)
    });
  }

  /* ---------- Reset demo data ----------
     The mock's version rewrote its own localStorage seeds. The real one is an
     admin operation: `reset` removes the rows the seeder created (and keeps any
     record the admin's own data still references), then `seed` recreates them.
     It is a whole-dataset reset — airlines, flights, accounts and bookings — so
     the confirmation says so rather than promising a bookings-only refresh. */
  $('#resetBtn').addEventListener('click', () => {
    askConfirm({
      title: 'Reset the demo data?',
      message: 'This removes the rows the seeder created — demo airlines, flights, accounts and bookings '
        + '— and recreates them. Records you created yourself are kept. Use it to put the demo back on a '
        + 'known footing, not to refresh this table.',
      yesLabel: 'Reset demo data',
      icon: 'rotate-left',
      onYes: () => {
        const btn = $('#resetBtn');
        btn.disabled = true;
        apiPost('/api/admin/reset')
          .then(() => apiPost('/api/admin/seed'))
          .then(() => {
            showToast('Demo data reset and re-seeded.', 'success');
            state.page = 1;
            return reload();
          })
          .catch((err) => {
            showToast((err && err.message) || 'The demo data could not be reset.', 'error');
          })
          .finally(() => {
            btn.disabled = false;
          });
      }
    });
  });

  /* ---------- Booking detail modal ----------
     A FRESH read of the row, not the cached one: a payment taken since the list
     was drawn shows up here without a page reload, and the Cancel button is
     decided from the server's copy. */
  const detailModal = $('#bookingModal');

  function openDetail(id, button) {
    if (button) button.disabled = true;
    apiGet('/api/admin/bookings/' + id)
      .then((b) => {
        currentDetailId = String(b.id);
        const f = b.flight || {};
        const pay = b.payment || {};

        $('#bookingModalTitle').textContent = `Booking ${b.pnr || b.id}`;
        $('#dBkgId').textContent = b.id;
        $('#dPnr').textContent = b.pnr || '—';
        $('#dTicketNo').textContent = b.ticketNo || '—';
        $('#dStatus').innerHTML =
          `<span class="badge ${bookingBadge(b.status)}">${escapeHtml(b.status || '—')}</span>`;
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
        $('#dFare').textContent = f.pricePerPassenger
          ? `${fmtNPR(f.pricePerPassenger)} / pax` : '—';
        $('#dTotal').textContent = fmtNPR(b.amount);

        const pax = Array.isArray(b.passengers) ? b.passengers : [];
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

        $('#dCancelBtn').hidden = b.status !== 'Confirmed';
        detailModal.hidden = false;
      })
      .catch((err) => {
        showToast('Could not open that booking: ' + ((err && err.message) || err), 'error');
      })
      .finally(() => {
        if (button) button.disabled = false;
      });
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
    const b = bookings.find((x) => String(x.id) === String(currentDetailId));
    if (!b) return;
    closeDetail();
    cancelBooking(b, null);
  });

  /* ---------- Row action wiring (event delegation)
     There is no delete branch: the API has no booking delete, and the mock's
     `_seed`-only trash had nothing left to act on once the seeds are the
     database's. Cancel is the module's disable. */
  $('#bookingTableBody').addEventListener('click', (e) => {
    const viewBtn = e.target.closest('[data-view]');
    const confirmBtn = e.target.closest('[data-confirm]');
    const cancelBtn = e.target.closest('[data-cancel]');

    if (viewBtn) openDetail(viewBtn.dataset.view, viewBtn);

    if (confirmBtn) {
      const b = bookings.find((x) => String(x.id) === confirmBtn.dataset.confirm);
      if (b) confirmBooking(b, confirmBtn);
    }
    if (cancelBtn) {
      const b = bookings.find((x) => String(x.id) === cancelBtn.dataset.cancel);
      if (b) cancelBooking(b, cancelBtn);
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

  /* ---------- First render ----------
     No seed fallback: if the API cannot be reached the page says so, rather than
     drawing bookings that are not in the database. */
  reload().catch(showLoadFailure);
});
