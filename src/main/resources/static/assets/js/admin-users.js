/* =========================================
   YATRA ADMIN — USERS PAGE JS
   User management CRUD (Master Plan §2.1 #6).
   Data lives in localStorage `yatra_admin_users`
   until the Spring API replaces it — the store
   self-seeds on first open so the dashboard's
   Total Users stat reads a real count.

   Rules baked in:
   - ADMIN accounts are protected: editable,
     never disabled or deleted (a disabled admin
     could lock everyone out of the panel —
     the backend's RBAC will own that later).
   - No password field: passwords belong to
     the backend (BCrypt) and are never stored
     or displayed here (Master Plan §3.4; the
     spec's DTO rule says never expose hashes).
   - Deleting a user keeps their bookings: the
     bookings store references customers by
     name/email snapshot, not by user id, so
     records stay intact — matching how the
     future API would soft-reference them.
   - When the Spring API lands, the store read
     + CRUD swap for apiGet()/apiPost() calls
     against /api/users.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const STORAGE_KEY = 'yatra_admin_users';
  const PAGE_SIZE = 8;

  /* ---------- Seed data (self-seeds on first read) ----------
     The roster now lives in mock-data.js (MockDB.SEED_USERS) — the data
     layer owns every mock store, and registration (POST /api/auth/register)
     appends to the SAME roster this page manages, so the two can never
     drift apart. MockDB.getUsers() self-seeds, so load() below is only a
     fallback for the (never expected) API failure path. */
  const SEED_USERS = MockDB.SEED_USERS;

  /* ---------- State (mock "repository") ---------- */
  let users = []; // initial read goes through the API layer (item 16)
  let state = { search: '', role: 'ALL', status: 'ALL', page: 1 };
  let confirmAction = null; // queue for the styled confirm modal

  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const data = JSON.parse(raw);
        if (Array.isArray(data)) return data;
      }
    } catch (err) { /* corrupted storage → reseed */ }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(SEED_USERS));
    return [...SEED_USERS];
  }

  function save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(users));
    } catch (err) {
      toast('Could not save — storage quota reached.', 'error');
    }
  }

  /* Older/edge records may lack fields — normalize for display. */
  function normalize(u) {
    if (!u || typeof u !== 'object') return;
    if (!u.id) u.id = Date.now();
    if (!u.name) u.name = 'Unnamed';
    if (!u.email) u.email = '—';
    if (!u.role) u.role = 'USER';
    if (!u.status) u.status = 'Active';
    if (!u.registeredAt) u.registeredAt = '';
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
  function filtered() {
    const q = state.search.trim().toLowerCase();
    return users.filter((u) => {
      const matchesQ =
        !q ||
        u.name.toLowerCase().includes(q) ||
        u.email.toLowerCase().includes(q) ||
        (u.phone || '').toLowerCase().includes(q) ||
        String(u.id).includes(q);
      const matchesRole = state.role === 'ALL' || u.role === state.role;
      const matchesStatus = state.status === 'ALL' || u.status === state.status;
      return matchesQ && matchesRole && matchesStatus;
    });
  }

  const totalPages = () => Math.max(1, Math.ceil(filtered().length / PAGE_SIZE));

  function clampPage() {
    state.page = Math.min(Math.max(1, state.page), totalPages());
  }

  /* ---------- Formatting helpers ---------- */
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

  const roleBadge = (r) => (r === 'ADMIN' ? 'badge-warning' : 'badge-neutral');
  const statusBadge = (s) => (s === 'Active' ? 'badge-success' : 'badge-neutral');

  /* ---------- Table render ---------- */
  function render() {
    clampPage();
    const rows = filtered();
    const start = (state.page - 1) * PAGE_SIZE;
    const pageRows = rows.slice(start, start + PAGE_SIZE);
    pageRows.forEach(normalize);

    const tbody = $('#userTableBody');
    tbody.innerHTML = pageRows
      .map((u) => {
        const isAdmin = u.role === 'ADMIN';
        const initial = (u.name || '?').trim().charAt(0).toUpperCase();
        return `
      <tr>
        <td>
          <div class="user-cell">
            <span class="admin-avatar" aria-hidden="true">${escapeHtml(initial)}</span>
            <span class="user-cell-info">
              <strong>${escapeHtml(u.name)}</strong>
              <span class="cell-sub">${escapeHtml(u.email)}</span>
            </span>
          </div>
        </td>
        <td>${escapeHtml(u.phone || '—')}</td>
        <td><span class="badge ${roleBadge(u.role)}">${escapeHtml(u.role)}</span></td>
        <td>${escapeHtml(fmtDate(u.registeredAt))}</td>
        <td><span class="badge ${statusBadge(u.status)}">${escapeHtml(u.status)}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-edit="${u.id}" aria-label="Edit ${escapeHtml(u.name)}"><i class="fa-solid fa-pen"></i></button>
            ${isAdmin
            ? `<span class="cell-sub" title="Admin accounts are protected — RBAC ownership moves to the backend"><i class="fa-solid fa-lock"></i></span>`
            : `<button type="button" class="icon-btn" data-toggle="${u.id}" aria-label="${u.status === 'Active' ? 'Disable' : 'Activate'} ${escapeHtml(u.name)}"><i class="fa-solid fa-${u.status === 'Active' ? 'power-off' : 'rotate-left'}"></i></button>
                 <button type="button" class="icon-btn icon-btn-danger" data-delete="${u.id}" aria-label="Delete ${escapeHtml(u.name)}"><i class="fa-solid fa-trash"></i></button>`}
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = rows.length > 0;
    $('#resultCount').textContent = `${rows.length} user${rows.length === 1 ? '' : 's'}`;
    renderPagination(rows.length);
  }

  function renderPagination(totalRows) {
    const pages = totalPages();
    $('#pageInfo').textContent = totalRows
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(state.page * PAGE_SIZE, totalRows)} of ${totalRows} users`
      : 'No users';

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
  $('#userSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    render();
  });

  $('#roleFilter').addEventListener('change', (e) => {
    state.role = e.target.value;
    state.page = 1;
    render();
  });

  $('#statusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
    state.page = 1;
    render();
  });

  /* ---------- Styled confirm modal (shared pattern) ---------- */
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

  function openModal(user = null) {
    form.reset();
    clearErrors();
    $('#userId').value = user ? user.id : '';
    $('#userName').value = user ? user.name : '';
    $('#userEmail').value = user ? (user.email === '—' ? '' : user.email) : '';
    $('#userPhone').value = user ? user.phone || '' : '';
    $('#userRole').value = user ? user.role : 'USER';
    $('#userStatus').value = user ? user.status : 'Active';
    $('#modalTitle').textContent = user ? 'Edit User' : 'Add User';
    modal.hidden = false;
    $('#userName').focus({ preventScroll: true });
  }

  function closeModal() {
    modal.hidden = true;
  }

  $('#addUserBtn').addEventListener('click', () => openModal());
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalCancel').addEventListener('click', closeModal);
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (!confirmModal.hidden) { hideConfirm(); return; }
      if (!modal.hidden) closeModal();
    }
  });

  /* ---------- Validation ---------- */
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
    } else if (
      users.some(
        (u) =>
          u.email.toLowerCase() === email.value.trim().toLowerCase() &&
          String(u.id) !== $('#userId').value
      )
    ) {
      setError(email, 'A user with this email already exists');
      ok = false;
    }

    if (phone.value.trim() && !/^9[678]\d{8}$/.test(phone.value.trim())) {
      setError(phone, 'Nepali mobile: 10 digits starting 96/97/98');
      ok = false;
    }

    if (!ok) toast('Please fix the highlighted fields.', 'error');
    return ok;
  }

  /* ---------- Create / Update ---------- */
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    if (!validate()) return;

    const id = $('#userId').value;
    const payload = {
      name: $('#userName').value.trim(),
      email: $('#userEmail').value.trim().toLowerCase(),
      phone: $('#userPhone').value.trim(),
      role: $('#userRole').value,
      status: $('#userStatus').value
    };

    if (id) {
      const u = users.find((x) => String(x.id) === id);
      const wasAdmin = u.role === 'ADMIN';
      Object.assign(u, payload);
      // Admins stay Active — the form lock mirrors the row lock.
      if (wasAdmin) u.status = 'Active';
      toast(`${payload.name} updated.`, 'success');
    } else {
      payload.id = users.length ? Math.max(...users.map((u) => u.id)) + 1 : 1;
      payload.registeredAt = new Date().toISOString();
      users.push(payload);
      toast(`${payload.name} added.`, 'success');
    }

    save();
    closeModal();
    render();
  });

  /* ---------- Toggle / Delete (admin accounts protected) ---------- */
  function toggleUser(u) {
    if (u.role === 'ADMIN') return; // belt-and-braces: UI already hides the button
    u.status = u.status === 'Active' ? 'Inactive' : 'Active';
    save();
    render();
    toast(`${u.name} is now ${u.status.toLowerCase()}.`, 'success');
  }

  function deleteUser(u) {
    askConfirm({
      title: 'Delete this user?',
      message: `${u.name} (${u.email}) will be removed permanently. Their past bookings are kept — records snapshot the customer's name, not the account.`,
      yesLabel: 'Delete user',
      icon: 'trash',
      onYes: () => {
        users = users.filter((x) => x.id !== u.id);
        save();
        render();
        toast(`${u.name} deleted — bookings kept.`, 'success');
      }
    });
  }

  /* ---------- Row action wiring (event delegation) ---------- */
  $('#userTableBody').addEventListener('click', (e) => {
    const editBtn = e.target.closest('[data-edit]');
    const toggleBtn = e.target.closest('[data-toggle]');
    const deleteBtn = e.target.closest('[data-delete]');

    if (editBtn) {
      const u = users.find((x) => String(x.id) === editBtn.dataset.edit);
      if (u) openModal(u);
    }
    if (toggleBtn) {
      const u = users.find((x) => String(x.id) === toggleBtn.dataset.toggle);
      if (u) toggleUser(u);
    }
    if (deleteBtn) {
      const u = users.find((x) => String(x.id) === deleteBtn.dataset.delete);
      if (u) deleteUser(u);
    }
  });

  /* ---------- First render (API read — item 16) ---------- */
  apiGet('/api/admin/users')
    .then((resp) => {
      users = Array.isArray(resp.users) && resp.users.length ? resp.users : load();
      render();
    })
    .catch(() => {
      users = load(); // mock fallback (also seeds)
      render();
    });
});
