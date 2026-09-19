/* =========================================
   YATRA ADMIN — PAYMENTS PAGE JS
   eSewa transaction monitoring (Master Plan §2.1 #8 / §3.6). Fully wired to
   the backend as of the fix-plan §12/§13 pass — the same migration
   admin-flights.js, admin-airlines.js, admin-destinations.js, admin-bookings.js
   and admin-users.js went through.

   Reads  : GET /api/admin/payments              (search + method + status + paging, server-side)
            GET /api/admin/bookings/{id}         (the detail modal's FRESH copy)
   Writes : POST /api/admin/payments/{id}/refund (the module's only write)

   All of them through api.js, so this file contains no localStorage access and
   no store of its own. It used to derive one transaction per booking out of
   `yatra_bookings` — the storefront's key (`esewaConfirm.js` wrote it) — with a
   `readBookings()`/`saveBookings()` pair, an in-memory `filtered()` and a
   client-side page slice, and `GET /api/admin/payments` behind a `.catch()` that
   silently fell back to those seeds. Refunding a transaction therefore changed a
   JSON array in the browser while the payment row in MySQL stayed SUCCESS: the
   page said "Refunded", the database said otherwise, and nothing errored.

   Rules baked in, and where they come from:
   - **There is no payments table to CRUD.** A payment is the record of what the
     gateway answered: amounts, transaction ids and dates are eSewa's, so nothing
     here is created, edited field by field or deleted. The one decision an admin
     makes is *was this money given back*, and that is a named action —
     `POST /{bookingId}/refund` — not a status field. The payment's row is
     addressed by its **booking** id, which is the id the table's buttons carry
     (`data-view`/`data-refund`) and the id every other admin page speaks; a
     payment is 1:1 with its booking, so the two name the same transaction.
   - **A refund is a payment action, not a cancellation.** The API marks the
     payment REFUNDED and the booking's `paymentStatus` Refunded, and touches
     nothing else: the booking is not cancelled and its seats stay counted (R4's
     policy). `admin-bookings.html`'s Cancel is the separate decision, which is why
     the confirmation text below says exactly that instead of promising a seat
     release — the mock's version did the same thing locally and could not have.
   - **It refuses a payment that never succeeded** (409 `PAYMENT_NOT_REFUNDABLE`,
     there is no money to give back) and is **idempotent** for one already
     refunded. Both are the server's rules and the server's sentences: this page
     predicts neither, it shows the toast it is handed.
   - **The detail modal is a fresh read**, not the cached row. A payment taken
     since the list was drawn is visible without reloading, and the refund button
     is decided from the server's copy — the same fix step 4 of §12 asks of every
     module's edit modal.
   - **Reads are QUERIES.** Search, method and status travel to MySQL, the count
     line and the pagination come back from it, and each read carries a sequence
     number so a slow answer for an older query cannot redraw the table (the bug
     admin-bookings.js's walk caught).
   - **The four tiles are the database's numbers too.** They used to be summed in
     the browser over the whole list, which stops being possible the moment the
     table pages server-side — "Collected" would shrink on page 2. They now come
     back as `resp.stats`, computed by `sum`/`count` queries over the whole ledger
     in the same request, and deliberately ignore the toolbar filters (the tile row
     summarises the gateway's traffic, not the current search).
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 8;

  /* ---------- State ----------
     `payments` is ONE PAGE of the ledger — never the whole table. The tiles live
     in `stats`, straight from the server, and are never recomputed from `payments`. */
  let payments = [];
  let stats = null;
  let state = {
    search: '', method: 'ALL', status: 'ALL',
    page: 1, totalPages: 1, totalElements: 0
  };
  let confirmAction = null;   // queue for the styled confirm modal
  let currentId = null;       // the booking id the detail modal is showing
  let readSeq = 0;

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page. */

  /* ---------- Query, not filtering ----------
     `page` is 1-based here and 0-based on the wire (Spring's convention). The two
     filter vocabularies are the page's own and are the ones the endpoint accepts:
     `method` is eSewa / Linked Bank Account (the API answers 400 for anything else,
     because it knows exactly two), and `status` is the title-case display
     vocabulary the badges already compare against (R16). `ALL` means "send
     nothing". */
  function listQuery() {
    const p = new URLSearchParams();
    if (state.search.trim()) p.set('search', state.search.trim());
    if (state.method !== 'ALL') p.set('method', state.method);
    if (state.status !== 'ALL') p.set('status', state.status);
    p.set('page', String(Math.max(0, state.page - 1)));
    p.set('size', String(PAGE_SIZE));
    return '/api/admin/payments?' + p.toString();
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

  /* Same status→color semantics as admin-bookings/admin-dashboard */
  const payBadge = (s) =>
    s === 'Paid' ? 'badge-success'
      : s === 'Pending' ? 'badge-warning'
        : s === 'Refunded' ? 'badge-info'
          : s === 'Failed' ? 'badge-danger' : 'badge-neutral';
  const bookingBadge = (s) => (s === 'Confirmed' ? 'badge-success' : 'badge-danger');

  /* ---------- One booking row = one transaction ----------
     The ledger answers booking-shaped rows (see AdminPaymentListResponse) and the
     page derives the transaction from one, exactly as it always did. `paymentStatus`
     is the booking column the API keeps in step with the payment row's own status —
     it is what the status column, the badge and the refund button read. */
  function toTxn(b) {
    const pay = (b.payment && typeof b.payment === 'object') ? b.payment : {};
    return {
      bkg: b,
      id: pay.txnId || b.id,   // fallback: the booking id when the row has no txn id yet
      method: pay.method || '',
      /* The payment row's amount is the booking's total by contract (`Payment.amount`
         is set from `Booking.totalAmount` on initiate and re-set on refund), so the
         booking's own figure is the transaction's. */
      amount: Number(b.amount || 0),
      paidAt: pay.paidAt || '',
      status: b.paymentStatus || ''
    };
  }

  /* ---------- Summary stats (the server's own numbers) ---------- */
  function renderStats() {
    const s = stats || { transactions: 0, collected: 0, refunded: 0, pending: 0 };
    $('#payTxns').textContent = s.transactions;
    $('#payCollected').textContent = fmtNPR(s.collected);
    $('#payRefunded').textContent = fmtNPR(s.refunded);
    $('#payPending').textContent = s.pending;
  }

  /* ---------- Table render ---------- */
  function render() {
    const tbody = $('#paymentTableBody');
    tbody.innerHTML = payments
      .map((b) => {
        const t = toTxn(b);
        const f = b.flight || {};
        /* A transaction can be refunded only while it is Paid — the same rule the
           API enforces with 409 PAYMENT_NOT_REFUNDABLE, drawn from the server's copy. */
        const canRefund = t.status === 'Paid';
        return `
      <tr>
        <td><strong>${escapeHtml(t.id)}</strong>
          <span class="cell-sub">${escapeHtml(fmtDateTime(t.paidAt))}</span></td>
        <td>
          <strong>${escapeHtml(b.customer || '—')}</strong>
          <span class="cell-sub">${escapeHtml(b.phone || '')}</span>
        </td>
        <td><strong>${escapeHtml(b.pnr || '—')}</strong>
          <span class="cell-sub">${escapeHtml(b.id)}</span></td>
        <td>${escapeHtml(t.method || '—')}</td>
        <td>${escapeHtml(fmtDate(t.paidAt))}</td>
        <td><strong>${fmtNPR(t.amount)}</strong></td>
        <td><span class="badge ${payBadge(t.status)}">${escapeHtml(t.status || '—')}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-view="${escapeHtml(b.id)}" aria-label="View transaction ${escapeHtml(t.id)}"><i class="fa-solid fa-eye"></i></button>
            ${canRefund ? `<button type="button" class="icon-btn icon-btn-danger" data-refund="${escapeHtml(b.id)}" aria-label="Refund transaction ${escapeHtml(t.id)}"><i class="fa-solid fa-hand-holding-dollar"></i></button>` : ''}
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = payments.length > 0;
    $('#resultCount').textContent =
      `${state.totalElements} transaction${state.totalElements === 1 ? '' : 's'}`;
    renderPagination();
    renderStats();
  }

  function renderPagination() {
    const pages = Math.max(1, state.totalPages);
    const total = state.totalElements;

    $('#pageInfo').textContent = total
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, total)}–${Math.min(state.page * PAGE_SIZE, total)} of ${total} transactions`
      : 'No transactions';

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
     Each one re-queries the backend and resets to page 1. Typing is debounced so a
     search fires one request, not one per keystroke. */
  let searchTimer;
  $('#paySearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(reloadOrToast, 250);
  });

  $('#payMethodFilter').addEventListener('change', (e) => {
    state.method = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  $('#payStatusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  /* ---------- Reads: one path, used on load, on every filter/page change and
     after every write ---------- */
  async function reload() {
    const seq = ++readSeq;
    const resp = await apiGet(listQuery());
    if (seq !== readSeq) return; // superseded: a newer read owns the table

    payments = Array.isArray(resp.bookings) ? resp.bookings : [];

    const total = Number(resp.totalElements);
    state.totalElements = Number.isFinite(total) ? total : payments.length;
    const pages = Number(resp.totalPages);
    state.totalPages = Number.isFinite(pages) && pages > 0
      ? pages
      : Math.max(1, Math.ceil(state.totalElements / PAGE_SIZE));

    /* The tiles ride on the same answer (the paged shape). Keeping the last known
       ones on an unpaged/absent field would be a number this page cannot justify, so
       they fall back to zero only when the response carried none at all. */
    if (resp.stats) stats = resp.stats;

    /* A filtered list can leave the view past the end of the result set (asking
       for page 3 of 2). The server answers that honestly — an empty page with the
       real counts — so step back once rather than rendering "No transactions"
       over a table that has some. */
    if (!payments.length && state.page > 1 && state.page > state.totalPages) {
      state.page = state.totalPages;
      return reload();
    }

    render();
  }

  function showLoadFailure(err) {
    payments = [];
    stats = null;
    state.totalElements = 0;
    state.totalPages = 1;
    render();
    showToast('Could not load payments: ' + ((err && err.message) || err), 'error');
  }

  function reloadOrToast() {
    return reload().catch((err) => {
      showToast('Could not load payments: ' + ((err && err.message) || err), 'error');
    });
  }

  function goToPage(page) {
    const last = Math.max(1, state.totalPages);
    state.page = Math.min(Math.max(1, page), last);
    reloadOrToast();
  }

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

  /* ---------- Refund = the API's own action, not a local status flip ---------- */
  function refund(id, button) {
    if (button) button.disabled = true;
    return apiPost('/api/admin/payments/' + id + '/refund')
      .then(() => {
        showToast(`Transaction for booking ${id} marked Refunded.`, 'success');
        return reload();
      })
      .catch((err) => {
        if (button) button.disabled = false;
        showToast((err && err.message) || 'The payment could not be refunded.', 'error');
      });
  }

  function askRefund(t, button) {
    askConfirm({
      title: 'Refund this payment?',
      message: `Transaction ${t.id} (${fmtNPR(t.amount)}, ${t.bkg.customer || 'customer'}) will be `
        + 'marked Refunded and so will the booking\'s payment status. Cancelling the booking is a '
        + 'separate decision — its seats stay counted on the flight, and a payment that never '
        + 'succeeded cannot be refunded at all.',
      yesLabel: 'Mark refunded',
      icon: 'hand-holding-dollar',
      onYes: () => refund(t.bkg.id, button)
    });
  }

  /* ---------- Payment detail modal ----------
     A FRESH read of the row, not the cached one. There is no
     `GET /api/admin/payments/{id}` because a payment is 1:1 with its booking, so the
     booking's own endpoint is the same record — the one `AdminBookingResponse` already
     shapes for this page — and a payment taken since the list was drawn shows up here
     without a page reload. */
  const detailModal = $('#payModal');

  function openDetail(id, button) {
    if (button) button.disabled = true;
    apiGet('/api/admin/bookings/' + id)
      .then((b) => {
        currentId = String(b.id);
        const t = toTxn(b);
        const f = b.flight || {};

        $('#payModalTitle').textContent = `Transaction ${t.id}`;
        $('#dTxnId').textContent = t.id;
        $('#dMethod').textContent = t.method || '—';
        $('#dPaidAt').textContent = fmtDateTime(t.paidAt);
        $('#dAmount').textContent = fmtNPR(t.amount);
        $('#dPayStatus').innerHTML =
          `<span class="badge ${payBadge(t.status)}">${escapeHtml(t.status || '—')}</span>`;

        $('#dBkgId').textContent = b.id;
        $('#dPnr').textContent = b.pnr || '—';
        $('#dTicketNo').textContent = b.ticketNo || '—';
        $('#dBkgStatus').innerHTML =
          `<span class="badge ${bookingBadge(b.status)}">${escapeHtml(b.status || '—')}</span>`;

        $('#dFlightNo').textContent = f.flightNo || '—';
        $('#dAirline').textContent = (f.airline && f.airline.name) || '—';
        $('#dRoute').textContent = f.from && f.to ? `${f.from} → ${f.to}` : '—';
        $('#dDate').textContent = f.date ? fmtDate(f.date) : '—';
        $('#dClass').textContent = f.flightClass || '—';

        $('#dCustName').textContent = b.customer || '—';
        $('#dCustPhone').textContent = b.phone || '—';
        $('#dCustEmail').textContent = b.email || '—';

        /* Decided from the server's copy: a refunded (or never-successful) payment
           offers no button, which is also what the API would answer. */
        $('#dRefundBtn').hidden = t.status !== 'Paid';
        detailModal.hidden = false;
      })
      .catch((err) => {
        showToast('Could not open that transaction: ' + ((err && err.message) || err), 'error');
      })
      .finally(() => {
        if (button) button.disabled = false;
      });
  }

  function closeDetail() {
    detailModal.hidden = true;
    currentId = null;
  }

  $('#payModalClose').addEventListener('click', closeDetail);
  $('#payModalDone').addEventListener('click', closeDetail);
  detailModal.addEventListener('click', (e) => {
    if (e.target === detailModal) closeDetail();
  });

  $('#dRefundBtn').addEventListener('click', () => {
    const b = payments.find((x) => String(x.id) === String(currentId));
    if (!b) return;
    const t = toTxn(b);
    closeDetail();
    askRefund(t, null);
  });

  /* ---------- Row action wiring (event delegation) ---------- */
  $('#paymentTableBody').addEventListener('click', (e) => {
    const viewBtn = e.target.closest('[data-view]');
    const refundBtn = e.target.closest('[data-refund]');

    if (viewBtn) openDetail(viewBtn.dataset.view, viewBtn);

    if (refundBtn) {
      const b = payments.find((x) => String(x.id) === String(refundBtn.dataset.refund));
      if (b) askRefund(toTxn(b), refundBtn);
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
     No seed fallback: the ledger is the database's, and if it cannot be read the
     page says so rather than drawing transactions MySQL has never seen. */
  reload().catch(showLoadFailure);
});
