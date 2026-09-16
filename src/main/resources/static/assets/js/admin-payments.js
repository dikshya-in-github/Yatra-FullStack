/* =========================================
   YATRA ADMIN — PAYMENTS PAGE JS
   eSewa transaction monitoring (Master Plan
   §2.1 #8 / §3.6). There is no separate
   payments store: every booking in
   `yatra_bookings` (written by the storefront
   on PAY success — esewaConfirm.js — and
   seeded by admin-bookings.js) carries its
   own `payment` object, so this page derives
   the transaction list from the bookings.

   Rules baked in:
   - READ-MOSTLY: the only write is a refund
     = status change (Paid → Refunded), which
     mirrors the booking's paymentStatus.
     Amounts, txn IDs and dates are never
     editable — that's the future backend
     PaymentService's job (Master Plan §3.6).
   - A booking record and its payment share
     one lifecycle: the page reads/writes
     `paymentStatus` on the booking itself so
     admin-bookings.html stays consistent.
   - Refunds recompute nothing on the flight
     (seats stay counted), matching the
     bookings page cancel rule.
   - When the Spring API lands, the bookings
     read + refund write swap for apiGet()/
     apiPost() calls against /api/payments.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const BOOKING_KEY = 'yatra_bookings'; // owned by the storefront (esewaConfirm.js)
  const PAGE_SIZE = 8;

  /* ---------- State (mock "repository") ---------- */
  let bookings = []; // initial read goes through the API layer (item 16)
  let state = { search: '', method: 'ALL', status: 'ALL', page: 1 };
  let confirmAction = null; // queue for the styled confirm modal
  let currentBkgId = null;

  function readBookings() {
    try {
      const raw = localStorage.getItem(BOOKING_KEY);
      const data = raw ? JSON.parse(raw) : [];
      return Array.isArray(data) ? data : [];
    } catch (err) {
      return [];
    }
  }

  function saveBookings() {
    try {
      localStorage.setItem(BOOKING_KEY, JSON.stringify(bookings));
    } catch (err) {
      toast('Could not save — storage quota reached.', 'error');
    }
  }

  /* Every booking = one transaction. Older/edge records may lack fields —
     normalize for display without disturbing the stored shape. */
  function toTxn(b) {
    const pay = (b.payment && typeof b.payment === 'object') ? b.payment : {};
    return {
      bkg: b,
      id: pay.txnId || b.id,          // fallback: booking id when no txn id
      method: pay.method || 'eSewa',
      amount: Number(pay.amount != null ? pay.amount : b.amount) || 0,
      paidAt: pay.paidAt || b.createdAt || '',
      status: b.paymentStatus || 'Paid',
      refundedAt: pay.refundedAt || ''
    };
  }

  /* ---------- Toast ---------- */
  let toastTimer;
  function toast(message, type = 'success') {
    const toastEl = $('#toast');
    toastEl.className = 'toast ' + type;
    toastEl.innerHTML = `<i class="fa-solid ${type === 'error' ? 'fa-circle-exclamation' : 'fa-circle-check'}"></i> ${message}`;
    requestAnimationFrame(() => toastEl.classList.add('show'));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('show'), 3200);
  }

  /* ---------- Filtering + pagination ---------- */
  function txns() {
    return bookings.map(toTxn);
  }

  function filtered() {
    const q = state.search.trim().toLowerCase();
    return txns().filter((t) => {
      const f = t.bkg.flight || {};
      const matchesQ =
        !q ||
        t.id.toLowerCase().includes(q) ||
        t.bkg.id.toLowerCase().includes(q) ||
        (t.bkg.pnr || '').toLowerCase().includes(q) ||
        (t.bkg.customer || '').toLowerCase().includes(q) ||
        (f.flightNo || '').toLowerCase().includes(q);
      const matchesMethod = state.method === 'ALL' || t.method === state.method;
      const matchesStatus = state.status === 'ALL' || t.status === state.status;
      return matchesQ && matchesMethod && matchesStatus;
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

  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* Same status→color semantics as admin-bookings/admin-dashboard */
  const payBadge = (s) =>
    s === 'Paid' ? 'badge-success'
      : s === 'Pending' ? 'badge-warning'
        : s === 'Refunded' ? 'badge-info'
          : s === 'Failed' ? 'badge-danger' : 'badge-neutral';
  const bookingBadge = (s) => (s === 'Confirmed' ? 'badge-success' : 'badge-danger');

  /* ---------- Summary stats ---------- */
  function renderStats() {
    const all = txns();
    const paid = all.filter((t) => t.status === 'Paid');
    const refunded = all.filter((t) => t.status === 'Refunded');
    const pending = all.filter((t) => t.status === 'Pending');

    $('#payTxns').textContent = all.length;
    $('#payCollected').textContent = fmtNPR(paid.reduce((s, t) => s + t.amount, 0));
    $('#payRefunded').textContent = fmtNPR(refunded.reduce((s, t) => s + t.amount, 0));
    $('#payPending').textContent = pending.length;
  }

  /* ---------- Table render ---------- */
  function render() {
    clampPage();
    const rows = filtered();
    const start = (state.page - 1) * PAGE_SIZE;
    const pageRows = rows.slice(start, start + PAGE_SIZE);

    const tbody = $('#paymentTableBody');
    tbody.innerHTML = pageRows
      .map((t) => {
        const b = t.bkg;
        const f = b.flight || {};
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
        <td>${escapeHtml(t.method)}</td>
        <td>${escapeHtml(fmtDate(t.paidAt))}</td>
        <td><strong>${fmtNPR(t.amount)}</strong></td>
        <td><span class="badge ${payBadge(t.status)}">${escapeHtml(t.status)}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-view="${escapeHtml(b.id)}" aria-label="View transaction ${escapeHtml(t.id)}"><i class="fa-solid fa-eye"></i></button>
            ${canRefund ? `<button type="button" class="icon-btn icon-btn-danger" data-refund="${escapeHtml(b.id)}" aria-label="Refund transaction ${escapeHtml(t.id)}"><i class="fa-solid fa-hand-holding-dollar"></i></button>` : ''}
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = rows.length > 0;
    $('#resultCount').textContent = `${rows.length} transaction${rows.length === 1 ? '' : 's'}`;
    renderPagination(rows.length);
    renderStats();
  }

  function renderPagination(totalRows) {
    const pages = totalPages();
    $('#pageInfo').textContent = totalRows
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(state.page * PAGE_SIZE, totalRows)} of ${totalRows} transactions`
      : 'No transactions';

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
  $('#paySearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    render();
  });

  $('#payMethodFilter').addEventListener('change', (e) => {
    state.method = e.target.value;
    state.page = 1;
    render();
  });

  $('#payStatusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
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

  /* ---------- Refund = payment status change (Paid → Refunded) ---------- */
  function refundTxn(t) {
    askConfirm({
      title: 'Refund this payment?',
      message: `Transaction ${t.id} (${fmtNPR(t.amount)}, ${t.bkg.customer || 'customer'}) will be marked Refunded. The booking record keeps its seats and the real money movement is handled by the backend payment flow later.`,
      yesLabel: 'Mark refunded',
      icon: 'hand-holding-dollar',
      onYes: () => {
        t.bkg.paymentStatus = 'Refunded';
        if (t.bkg.payment && typeof t.bkg.payment === 'object') {
          t.bkg.payment.refundedAt = new Date().toISOString();
        }
        saveBookings();
        render();
        if (currentBkgId === t.bkg.id) openDetail(t.bkg); // refresh an open modal
        toast(`${t.id} marked Refunded.`, 'success');
      }
    });
  }

  /* ---------- Payment detail modal (read-only; derived from the booking) ---------- */
  const detailModal = $('#payModal');

  function openDetail(b) {
    const t = toTxn(b);
    currentBkgId = b.id;
    const f = b.flight || {};

    $('#payModalTitle').textContent = `Transaction ${t.id}`;
    $('#dTxnId').textContent = t.id;
    $('#dMethod').textContent = t.method;
    $('#dPaidAt').textContent = fmtDateTime(t.paidAt);
    $('#dAmount').textContent = fmtNPR(t.amount);
    $('#dPayStatus').innerHTML = `<span class="badge ${payBadge(t.status)}">${escapeHtml(t.status)}</span>`;

    $('#dBkgId').textContent = b.id;
    $('#dPnr').textContent = b.pnr || '—';
    $('#dTicketNo').textContent = b.ticketNo || '—';
    $('#dBkgStatus').innerHTML = `<span class="badge ${bookingBadge(b.status)}">${escapeHtml(b.status || '—')}</span>`;

    $('#dFlightNo').textContent = f.flightNo || '—';
    $('#dAirline').textContent = (f.airline && f.airline.name) || '—';
    $('#dRoute').textContent = f.from && f.to ? `${f.from} → ${f.to}` : '—';
    $('#dDate').textContent = f.date ? fmtDate(f.date) : '—';
    $('#dClass').textContent = f.flightClass || '—';

    $('#dCustName').textContent = b.customer || '—';
    $('#dCustPhone').textContent = b.phone || '—';
    $('#dCustEmail').textContent = b.email || '—';

    const showRefundLine = t.status === 'Refunded' && !!t.refundedAt;
    $('#dRefundAtLabel').hidden = !showRefundLine;
    $('#dRefundAt').hidden = !showRefundLine;
    $('#dRefundAt').textContent = fmtDateTime(t.refundedAt);

    $('#dRefundBtn').hidden = t.status !== 'Paid';
    detailModal.hidden = false;
  }

  function closeDetail() {
    detailModal.hidden = true;
    currentBkgId = null;
  }

  $('#payModalClose').addEventListener('click', closeDetail);
  $('#payModalDone').addEventListener('click', closeDetail);
  detailModal.addEventListener('click', (e) => {
    if (e.target === detailModal) closeDetail();
  });

  $('#dRefundBtn').addEventListener('click', () => {
    const b = bookings.find((x) => x.id === currentBkgId);
    if (b) {
      closeDetail();
      refundTxn(toTxn(b));
    }
  });

  /* ---------- Row action wiring (event delegation) ---------- */
  $('#paymentTableBody').addEventListener('click', (e) => {
    const viewBtn = e.target.closest('[data-view]');
    const refundBtn = e.target.closest('[data-refund]');

    if (viewBtn) {
      const b = bookings.find((x) => x.id === viewBtn.dataset.view);
      if (b) openDetail(b);
    }
    if (refundBtn) {
      const b = bookings.find((x) => x.id === refundBtn.dataset.refund);
      if (b) refundTxn(toTxn(b));
    }
  });

  /* ---------- Escape closes the topmost modal ---------- */
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!confirmModal.hidden) { hideConfirm(); return; }
    if (!detailModal.hidden) closeDetail();
  });

  /* ---------- First render (API read — item 16) ---------- */
  apiGet('/api/admin/payments')
    .then((resp) => {
      bookings = Array.isArray(resp.bookings) ? resp.bookings : [];
      render();
    })
    .catch(() => {
      bookings = readBookings(); // mock fallback
      render();
    });
});
