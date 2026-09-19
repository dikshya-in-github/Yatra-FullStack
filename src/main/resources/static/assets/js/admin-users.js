/* =========================================
   YATRA ADMIN — USERS PAGE JS
   User management CRUD (Master Plan §2.1 #6). Fully wired to the
   backend as of the fix-plan §12/§13 pass — the same migration
   admin-flights.js, admin-airlines.js, admin-destinations.js and
   admin-bookings.js went through.

   Reads  : GET /api/admin/users              (search + role + status + paging, server-side)
            GET /api/admin/users/{id}         (the edit modal's FRESH copy)
            GET /api/admin/users/{id}/bookings (the bookings modal)
   Writes : POST   /api/admin/users           (create)
            PUT    /api/admin/users/{id}      (edit)
            PUT    /api/admin/users/{id}/status (the Activate/Disable toggle)
            DELETE /api/admin/users/{id}      (delete — refused for an account with history)

   All of them through api.js, so this file contains no localStorage
   access and no seed rows of its own. It used to own both (`yatra_admin_users`,
   `load()`/`save()`, `normalize()`), which meant every write here — create,
   edit, toggle, delete — changed a JSON array in the browser while MySQL held
   the roster. The store also *self-seeded* from `MockDB.SEED_USERS`, so the page
   could draw ten users the database had never heard of.

   Rules baked in:
   - **ADMIN accounts are protected, and the lock stays on the page.** The row
     shows a lock instead of toggle/delete, and the edit form disables the role
     and status selects for an admin (they are still submitted, with their
     current values, so the write is a no-op on both). The API enforces the same
     rule independently — 409 ADMIN_ACCOUNT_PROTECTED for demote, deactivate,
     delete, and for *creating* an Inactive admin — and the walk proves that
     refusal by asking the API directly, so the protection is verified twice
     rather than assumed once.
   - **A password is optional, and the page now offers it.** The old rule here was
     "the demo store never stores one", which was true and also why every account
     an admin created could never sign in. `AdminUserRequest.password` is the
     API's own way out; blank means *unchanged* on an edit (never "set it to
     blank") and *no credential* on a create. `canSignIn` comes back on every row,
     so the table says which accounts are actually usable.
   - **Deletes keep the bookings.** The API refuses with 409 USER_HAS_BOOKINGS
     when the account has history, and the page shows that sentence instead of
     guessing at it.
   - **The user's bookings are visible**: `GET /api/admin/users/{id}/bookings` was
     built for this page and had no caller at all — the same class of gap as the
     Airlines search that promised "name or IATA" and matched names.
   - Reads are QUERIES (search/role/status/page travel to MySQL, and the count
     line and pagination come back from it), and each read carries a sequence
     number so a slow answer for an older query cannot redraw the table.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 8;

  /* ---------- State ----------
     `users` is ONE PAGE of the server's answer — never the whole roster. */
  let users = [];
  let state = {
    search: '', role: 'ALL', status: 'ALL',
    page: 1, totalPages: 1, totalElements: 0
  };
  let readSeq = 0;
  let confirmAction = null;   // queue for the styled confirm modal
  let bookingsUserId = null;  // the account the bookings modal is showing

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page. */

  /* ---------- Query, not filtering ---------- */
  function listQuery() {
    const p = new URLSearchParams();
    if (state.search.trim()) p.set('search', state.search.trim());
    if (state.role !== 'ALL') p.set('role', state.role);
    if (state.status !== 'ALL') p.set('status', state.status);
    p.set('page', String(Math.max(0, state.page - 1)));
    p.set('size', String(PAGE_SIZE));
    return '/api/admin/users?' + p.toString();
  }

  /* ---------- Formatting ---------- */
  function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso.length > 10 ? iso : iso + 'T00:00:00');
    return isNaN(d) ? iso
      : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  function fmtNPR(n) {
    return 'NPR ' + Number(n || 0).toLocaleString('en-US', {
      minimumFractionDigits: 2, maximumFractionDigits: 2
    });
  }

  const roleBadge = (r) => (r === 'ADMIN' ? 'badge-warning' : 'badge-neutral');
  const statusBadge = (s) => (s === 'Active' ? 'badge-success' : 'badge-neutral');

  /* ---------- Table render ---------- */
  function render() {
    const tbody = $('#userTableBody');
    tbody.innerHTML = users
      .map((u) => {
        const isAdmin = u.role === 'ADMIN';
        const initial = (u.name || '?').trim().charAt(0).toUpperCase();
        /* `canSignIn` is the API's answer to "does this account have a credential
           and is it active" — the page used to have no idea, because it invented
           its accounts without one. */
        const noCredential = u.canSignIn === false
          ? '<span class="badge badge-neutral" title="No usable credential on file — this account cannot sign in until a password is set">No sign-in</span>'
          : '';
        return `
      <tr>
        <td>
          <div class="user-cell">
            <span class="admin-avatar" aria-hidden="true">${escapeHtml(initial)}</span>
            <span class="user-cell-info">
              <strong>${escapeHtml(u.name)}</strong>
              <span class="cell-sub">${escapeHtml(u.email || '—')}</span>
            </span>
            ${noCredential}
          </div>
        </td>
        <td>${escapeHtml(u.phone || '—')}</td>
        <td><span class="badge ${roleBadge(u.role)}">${escapeHtml(u.role)}</span></td>
        <td>${escapeHtml(fmtDate(u.registeredAt))}</td>
        <td><span class="badge ${statusBadge(u.status)}">${escapeHtml(u.status)}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-bookings="${u.id}" aria-label="Bookings for ${escapeHtml(u.name)}"><i class="fa-solid fa-receipt"></i></button>
            <button type="button" class="icon-btn" data-edit="${u.id}" aria-label="Edit ${escapeHtml(u.name)}"><i class="fa-solid fa-pen"></i></button>
            ${isAdmin
            ? `<span class="cell-sub" title="Admin accounts are protected — the API refuses demote, deactivate and delete for them too"><i class="fa-solid fa-lock"></i></span>`
            : `<button type="button" class="icon-btn" data-toggle="${u.id}" aria-label="${u.status === 'Active' ? 'Disable' : 'Activate'} ${escapeHtml(u.name)}"><i class="fa-solid fa-${u.status === 'Active' ? 'power-off' : 'rotate-left'}"></i></button>
                 <button type="button" class="icon-btn icon-btn-danger" data-delete="${u.id}" aria-label="Delete ${escapeHtml(u.name)}"><i class="fa-solid fa-trash"></i></button>`}
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = users.length > 0;
    $('#resultCount').textContent =
      `${state.totalElements} user${state.totalElements === 1 ? '' : 's'}`;
    renderPagination();
  }

  function renderPagination() {
    const pages = Math.max(1, state.totalPages);
    const total = state.totalElements;

    $('#pageInfo').textContent = total
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, total)}–${Math.min(state.page * PAGE_SIZE, total)} of ${total} users`
      : 'No users';

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

  /* ---------- Toolbar events ---------- */
  let searchTimer;
  $('#userSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(reloadOrToast, 250);
  });

  $('#roleFilter').addEventListener('change', (e) => {
    state.role = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  $('#statusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  /* ---------- Reads ---------- */
  async function reload() {
    const seq = ++readSeq;
    const resp = await apiGet(listQuery());
    /* Three controls can be in flight together here; a superseded answer must not
       redraw the table (see the note in admin-bookings.js — the walk caught it). */
    if (seq !== readSeq) return;

    users = Array.isArray(resp.users) ? resp.users : [];

    const total = Number(resp.totalElements);
    state.totalElements = Number.isFinite(total) ? total : users.length;
    const pages = Number(resp.totalPages);
    state.totalPages = Number.isFinite(pages) && pages > 0
      ? pages
      : Math.max(1, Math.ceil(state.totalElements / PAGE_SIZE));

    if (!users.length && state.page > 1 && state.page > state.totalPages) {
      state.page = state.totalPages;
      return reload();
    }

    render();
  }

  function showLoadFailure(err) {
    users = [];
    state.totalElements = 0;
    state.totalPages = 1;
    render();
    showToast('Could not load users: ' + ((err && err.message) || err), 'error');
  }

  function reloadOrToast() {
    return reload().catch((err) => {
      showToast('Could not load users: ' + ((err && err.message) || err), 'error');
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

  /* ---------- Add/Edit modal ---------- */
  const modal = $('#userModal');
  const form = $('#userForm');

  function fillForm(user) {
    const isAdmin = !!user && user.role === 'ADMIN';
    $('#userId').value = user ? user.id : '';
    $('#userName').value = user ? user.name : '';
    $('#userEmail').value = user ? user.email || '' : '';
    $('#userPhone').value = user ? user.phone || '' : '';
    $('#userRole').value = user ? user.role : 'USER';
    $('#userStatus').value = user ? user.status : 'Active';
    $('#userPassword').value = '';
    $('#modalTitle').textContent = user ? 'Edit User' : 'Add User';

    /* The page's lock, and the API's rule made visible: an admin account is
       editable (name, email, mobile, password) but never demoted or disabled. The
       selects keep their real values and stay submitted, so the write is a no-op
       on both fields — and if anything ever did get past this, the service refuses
       it with 409 ADMIN_ACCOUNT_PROTECTED. */
    $('#userRole').disabled = isAdmin;
    $('#userStatus').disabled = isAdmin;
    $('#lockNote').hidden = !isAdmin;

    $('#passwordHint').textContent = user
      ? 'Leave blank to keep the current password. An account with no credential cannot sign in.'
      : 'Optional. Without one the account exists but cannot sign in until a password is set.';
  }

  function openModal() {
    form.reset();
    clearErrors();
    fillForm(null);
    modal.hidden = false;
    $('#userName').focus({ preventScroll: true });
  }

  /* Edit opens on the SERVER's copy of that row (§12 step 4), fetched fresh. */
  function openEditModal(id, button) {
    if (button) button.disabled = true;
    apiGet('/api/admin/users/' + id)
      .then((user) => {
        form.reset();
        clearErrors();
        fillForm(user);
        modal.hidden = false;
        $('#userName').focus({ preventScroll: true });
      })
      .catch((err) => {
        showToast('Could not open that account: ' + ((err && err.message) || err), 'error');
      })
      .finally(() => {
        if (button) button.disabled = false;
      });
  }

  function closeModal() {
    modal.hidden = true;
  }

  $('#addUserBtn').addEventListener('click', openModal);
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalCancel').addEventListener('click', closeModal);
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
  });

  /* ---------- Validation ----------
     Format and required checks are local. Uniqueness is the SERVER's: this page
     holds one page of the roster, so "that email is taken" can only be answered by
     the endpoint (409 EMAIL_EXISTS / PHONE_EXISTS, landed inline on the field). */
  function clearErrors() {
    form.querySelectorAll('.a-field.has-error').forEach((f) => f.classList.remove('has-error'));
    form.querySelectorAll('.error-msg').forEach((m) => m.remove());
  }

  function setError(inputEl, message) {
    const field = inputEl.closest('.a-field');
    field.classList.add('has-error');
    const msg = document.createElement('small');
    msg.className = 'error-msg';
    msg.textContent = message;
    field.appendChild(msg);
  }

  function validate() {
    clearErrors();
    let ok = true;

    const name = $('#userName');
    const email = $('#userEmail');
    const phone = $('#userPhone');
    const password = $('#userPassword');

    if (!name.value.trim()) {
      setError(name, 'Full name is required');
      ok = false;
    }

    if (!email.value.trim()) {
      setError(email, 'Email is required');
      ok = false;
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value.trim())) {
      setError(email, 'Enter a valid email address');
      ok = false;
    }

    if (phone.value.trim() && !/^9[678]\d{8}$/.test(phone.value.trim())) {
      setError(phone, 'Nepali mobile: 10 digits starting 96/97/98');
      ok = false;
    }

    /* Mirrors AdminUserRequest's rule exactly: blank is legal (it means
       "unchanged" on an edit and "no credential" on a create), anything shorter
       than eight characters is not. */
    if (password.value && password.value.length < 8) {
      setError(password, 'Password must be at least 8 characters');
      ok = false;
    }

    if (!ok) showToast('Please fix the highlighted fields.', 'error');
    return ok;
  }

  /* ---------- Create / Update ---------- */
  function payload() {
    return {
      name: $('#userName').value.trim(),
      email: $('#userEmail').value.trim().toLowerCase(),
      phone: $('#userPhone').value.trim(),
      role: $('#userRole').value,
      status: $('#userStatus').value,
      password: $('#userPassword').value
    };
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!validate()) return;

    const id = $('#userId').value;
    const body = payload();
    const saveBtn = $('#userSaveBtn');
    if (saveBtn) saveBtn.disabled = true;

    try {
      if (id) {
        await apiPut('/api/admin/users/' + id, body);
      } else {
        await apiPost('/api/admin/users', body);
      }
    } catch (err) {
      if (err && err.code === 'EMAIL_EXISTS') setError($('#userEmail'), err.message);
      if (err && err.code === 'PHONE_EXISTS') setError($('#userPhone'), err.message);
      if (saveBtn) saveBtn.disabled = false;
      showToast((err && err.message) || 'The account could not be saved.', 'error');
      return;
    }

    if (saveBtn) saveBtn.disabled = false;
    closeModal();
    showToast(`${body.name} ${id ? 'updated' : 'added'}.`, 'success');
    await reloadOrToast();
  });

  /* ---------- Row actions ---------- */
  $('#userTableBody').addEventListener('click', (e) => {
    const bookingsBtn = e.target.closest('[data-bookings]');
    const editBtn = e.target.closest('[data-edit]');
    const toggleBtn = e.target.closest('[data-toggle]');
    const deleteBtn = e.target.closest('[data-delete]');

    if (bookingsBtn) openBookings(bookingsBtn.dataset.bookings, bookingsBtn);
    if (editBtn) openEditModal(editBtn.dataset.edit, editBtn);

    /* Activate/Disable is its own endpoint here (PUT /{id}/status) and is
       idempotent — the same value twice is a no-op, not an error. */
    if (toggleBtn) {
      const u = users.find((x) => String(x.id) === toggleBtn.dataset.toggle);
      if (!u) return;
      const next = u.status === 'Active' ? 'Inactive' : 'Active';
      toggleBtn.disabled = true;
      apiPut('/api/admin/users/' + u.id + '/status', { status: next })
        .then(() => {
          showToast(`${u.name} is now ${next.toLowerCase()}.`, 'success');
          return reload();
        })
        .catch((err) => {
          // An admin row should never get here (the lock hides the button), and the
          // API refuses it anyway with 409 ADMIN_ACCOUNT_PROTECTED.
          toggleBtn.disabled = false;
          showToast((err && err.message) || 'The account could not be updated.', 'error');
        });
    }

    if (deleteBtn) {
      const u = users.find((x) => String(x.id) === deleteBtn.dataset.delete);
      if (u) deleteUser(u, deleteBtn);
    }
  });

  /* An account with history is never deleted: the API answers 409
     USER_HAS_BOOKINGS and the row stays exactly where it was. */
  function deleteUser(u, button) {
    askConfirm({
      title: 'Delete this user?',
      message: `${u.name} (${u.email || u.phone || u.id}) will be removed. Their booking records are kept — `
        + 'the database refuses the delete if the account has any.',
      yesLabel: 'Delete user',
      icon: 'trash',
      onYes: () => {
        button.disabled = true;
        apiDelete('/api/admin/users/' + u.id)
          .then(() => {
            showToast(`${u.name} deleted.`, 'success');
            return reload();
          })
          .catch((err) => {
            button.disabled = false;
            showToast((err && err.message) || 'The account could not be deleted.', 'error');
          });
      }
    });
  }

  /* ---------- Bookings modal ----------
     `GET /api/admin/users/{id}/bookings` was built for this page in Phase 11 and
     had no caller: an admin could see that an account had history (the delete
     refusal names the count) but not what it was. */
  const bookingsModal = $('#bookingsModal');

  function openBookings(id, button) {
    if (button) button.disabled = true;
    apiGet('/api/admin/users/' + id + '/bookings')
      .then((resp) => {
        bookingsUserId = String(id);
        const rows = Array.isArray(resp.bookings) ? resp.bookings : [];
        $('#bookingsTitle').textContent = 'Bookings';
        $('#bookingsMeta').textContent = rows.length
          ? `${rows.length} booking${rows.length === 1 ? '' : 's'} on this account`
          : 'This account has no bookings.';
        $('#bookingsBody').innerHTML = rows.length
          ? rows.map((b) => {
            const f = b.flight || {};
            return `
        <tr>
          <td><strong>${escapeHtml(b.pnr || b.id)}</strong></td>
          <td>${escapeHtml(f.flightNo || '—')}<span class="cell-sub">${escapeHtml((f.airline && f.airline.name) || '')}</span></td>
          <td>${escapeHtml(f.from && f.to ? `${f.from} → ${f.to}` : '—')}<span class="cell-sub">${escapeHtml(f.date ? fmtDate(f.date) : '')}</span></td>
          <td>${fmtNPR(b.amount)}</td>
          <td><span class="badge ${b.status === 'Confirmed' ? 'badge-success' : 'badge-danger'}">${escapeHtml(b.status || '—')}</span></td>
        </tr>`;
          }).join('')
          : '<tr><td colspan="5" style="text-align:center; color: var(--slate-light);">No bookings on this account.</td></tr>';
        bookingsModal.hidden = false;
      })
      .catch((err) => {
        showToast('Could not load that account\'s bookings: ' + ((err && err.message) || err),
          'error');
      })
      .finally(() => {
        if (button) button.disabled = false;
      });
  }

  function closeBookings() {
    bookingsModal.hidden = true;
    bookingsUserId = null;
  }

  $('#bookingsClose').addEventListener('click', closeBookings);
  $('#bookingsDone').addEventListener('click', closeBookings);
  bookingsModal.addEventListener('click', (e) => {
    if (e.target === bookingsModal) closeBookings();
  });

  /* ---------- Escape closes the topmost modal ---------- */
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!confirmModal.hidden) { hideConfirm(); return; }
    if (!bookingsModal.hidden) { closeBookings(); return; }
    if (!modal.hidden) closeModal();
  });

  /* ---------- Utilities ---------- */
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* ---------- First render ----------
     No seed fallback: the roster is the database's, and if it cannot be read the
     page says so rather than drawing ten users MySQL has never heard of. */
  reload().catch(showLoadFailure);
});
