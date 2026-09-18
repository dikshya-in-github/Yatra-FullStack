/* =========================================
   YATRA ADMIN — AIRLINES PAGE JS
   CRUD (Master Plan §2.1 #3). Reads go through
   the API layer (item 16 — GET /api/airlines);
   writes stay in localStorage until the Spring
   API replaces them in Phase 4 (same data
   shapes either way, per the dynamic-readiness
   rule).
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const STORAGE_KEY = 'yatra_admin_airlines';
  const PAGE_SIZE = 5;

  /* ---------- Seed data (4 real Nepali carriers, per project scope) ---------- */
  const SEED_AIRLINES = [
    {
      id: 1,
      name: 'Buddha Air',
      iata: 'U4',
      description: "Nepal's largest domestic airline by fleet and destinations, operating ATR 42/72 aircraft.",
      status: 'Active',
      logo: 'assets/imgs/airline-buddha.jpg'
    },
    {
      id: 2,
      name: 'Yeti Airlines',
      iata: 'YT',
      description: 'Major domestic carrier connecting Kathmandu with Pokhara and other regional airports.',
      status: 'Active',
      logo: 'assets/imgs/airline-yeti.jpg'
    },
    {
      id: 3,
      name: 'Shree Airlines',
      iata: 'S3',
      description: 'Operates domestic routes and helicopter services across Nepal.',
      status: 'Active',
      logo: 'assets/imgs/airline-shree.svg'
    },
    {
      id: 4,
      name: 'Sita Air',
      iata: 'ST',
      description: 'Domestic airline serving scheduled and charter flights with Dornier and ATR aircraft.',
      status: 'Active',
      logo: 'assets/imgs/airline-sita.jpeg'
    }
  ];

  /* ---------- State (mock "repository") ---------- */
  let airlines = []; // initial read goes through the API layer (item 16)
  let state = { search: '', status: 'ALL', page: 1 };
  let logoData = null; // base64 data URL staged for the modal

  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        let data = JSON.parse(raw);
        if (Array.isArray(data)) {
          // Migration (2026-09-15): roster trimmed to 4 carriers — drop Summit
          // Air from seeds saved before the removal (IATA RM).
          const cleaned = data.filter((a) => a && a.iata !== 'RM');
          if (cleaned.length !== data.length) {
            data = cleaned;
            localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
          }
          return data;
        }
      }
    } catch (err) { /* corrupted storage → reseed */ }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(SEED_AIRLINES));
    return [...SEED_AIRLINES];
  }

  function save() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(airlines));
  }

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page,
     which also creates the #toast element it writes to. */

  /* ---------- Filtering + pagination ---------- */
  function filtered() {
    const q = state.search.trim().toLowerCase();
    return airlines.filter((a) => {
      const matchesQ =
        !q || a.name.toLowerCase().includes(q) || a.iata.toLowerCase().includes(q);
      const matchesStatus = state.status === 'ALL' || a.status === state.status;
      return matchesQ && matchesStatus;
    });
  }

  const totalPages = () => Math.max(1, Math.ceil(filtered().length / PAGE_SIZE));

  function clampPage() {
    state.page = Math.min(Math.max(1, state.page), totalPages());
  }

  /* ---------- Table render ---------- */
  function render() {
    clampPage();
    const rows = filtered();
    const start = (state.page - 1) * PAGE_SIZE;
    const pageRows = rows.slice(start, start + PAGE_SIZE);

    const tbody = $('#airlineTableBody');
    tbody.innerHTML = pageRows
      .map(
        (a) => `
      <tr>
        <td>${logoChip(a)}</td>
        <td><strong>${escapeHtml(a.name)}</strong></td>
        <td><span class="badge badge-neutral">${escapeHtml(a.iata)}</span></td>
        <td class="desc-cell">${escapeHtml(a.description || '—')}</td>
        <td><span class="badge ${a.status === 'Active' ? 'badge-success' : 'badge-neutral'}">${a.status}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-edit="${a.id}" aria-label="Edit ${escapeHtml(a.name)}"><i class="fa-solid fa-pen"></i></button>
            <button type="button" class="icon-btn" data-toggle="${a.id}" aria-label="${a.status === 'Active' ? 'Disable' : 'Activate'} ${escapeHtml(a.name)}"><i class="fa-solid fa-${a.status === 'Active' ? 'power-off' : 'rotate-left'}"></i></button>
            <button type="button" class="icon-btn icon-btn-danger" data-delete="${a.id}" aria-label="Delete ${escapeHtml(a.name)}"><i class="fa-solid fa-trash"></i></button>
          </div>
        </td>
      </tr>`
      )
      .join('');

    $('#emptyState').hidden = rows.length > 0;
    $('#resultCount').textContent = `${rows.length} airline${rows.length === 1 ? '' : 's'}`;
    renderPagination(rows.length);
  }

  /* Logo chip: image with IATA code-badge fallback (same pattern as searchFlight cards) */
  function logoChip(a) {
    if (a.logo && !a.logo.startsWith('data:')) {
      return `<span class="logo-chip"><img src="${a.logo}" alt="${escapeHtml(a.name)} logo"
        onerror="this.style.display='none'; this.nextElementSibling.style.display='flex';">
        <span class="logo-fallback" style="display:none;">${escapeHtml(a.iata)}</span></span>`;
    }
    if (a.logo && a.logo.startsWith('data:')) {
      return `<span class="logo-chip"><img src="${a.logo}" alt="${escapeHtml(a.name)} logo"></span>`;
    }
    return `<span class="logo-chip"><span class="logo-fallback">${escapeHtml(a.iata)}</span></span>`;
  }

  function renderPagination(totalRows) {
    const pages = totalPages();
    $('#pageInfo').textContent = totalRows
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(state.page * PAGE_SIZE, totalRows)} of ${totalRows} airlines`
      : 'No airlines';

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
  $('#airlineSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    render();
  });

  $('#statusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
    state.page = 1;
    render();
  });

  /* ---------- Modal open/close ---------- */
  const modal = $('#airlineModal');
  const form = $('#airlineForm');

  function openModal(airline = null) {
    form.reset();
    clearErrors();
    logoData = airline ? airline.logo || null : null;
    $('#airlineId').value = airline ? airline.id : '';
    $('#airlineName').value = airline ? airline.name : '';
    $('#airlineIata').value = airline ? airline.iata : '';
    $('#airlineStatus').value = airline ? airline.status : 'Active';
    $('#airlineDesc').value = airline ? airline.description || '' : '';
    $('#modalTitle').textContent = airline ? 'Edit Airline' : 'Add Airline';
    renderLogoPreview();
    modal.hidden = false;
    $('#airlineName').focus({ preventScroll: true });
  }

  function closeModal() {
    modal.hidden = true;
    logoData = null;
  }

  $('#addAirlineBtn').addEventListener('click', () => openModal());
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalCancel').addEventListener('click', closeModal);
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !modal.hidden) closeModal();
  });

  /* ---------- Logo upload preview (mock BLOB/Base64) ---------- */
  const logoInput = $('#logoInput');

  $('#uploadLogoBtn').addEventListener('click', () => logoInput.click());

  logoInput.addEventListener('change', () => {
    const file = logoInput.files && logoInput.files[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      showToast('Logo must be under 2 MB (demo limit).', 'error');
      logoInput.value = '';
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      logoData = reader.result; // base64 data URL — same shape the backend will store
      renderLogoPreview();
      showToast('Logo ready — saved with the airline.', 'success');
    };
    reader.onerror = () => showToast('Could not read that file.', 'error');
    reader.readAsDataURL(file);
  });

  $('#removeLogoBtn').addEventListener('click', () => {
    logoData = null;
    logoInput.value = '';
    renderLogoPreview();
  });

  function renderLogoPreview() {
    const preview = $('#logoPreview');
    const removeBtn = $('#removeLogoBtn');
    if (logoData) {
      preview.innerHTML = `<img src="${logoData}" alt="Logo preview">`;
      removeBtn.hidden = false;
    } else {
      preview.innerHTML = '<span>No file</span>';
      removeBtn.hidden = true;
    }
  }

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

    const name = $('#airlineName');
    const iata = $('#airlineIata');

    if (!name.value.trim()) {
      setError(name, 'Airline name is required');
      ok = false;
    } else if (
      airlines.some(
        (a) =>
          a.name.toLowerCase() === name.value.trim().toLowerCase() &&
          String(a.id) !== $('#airlineId').value
      )
    ) {
      setError(name, 'An airline with this name already exists');
      ok = false;
    }

    if (!iata.value.trim()) {
      setError(iata, 'IATA code is required');
      ok = false;
    } else if (!/^[A-Za-z0-9]{2}$/.test(iata.value.trim())) {
      setError(iata, 'IATA code must be exactly 2 letters/digits (e.g. U4)');
      ok = false;
    } else if (
      airlines.some(
        (a) =>
          a.iata.toUpperCase() === iata.value.trim().toUpperCase() &&
          String(a.id) !== $('#airlineId').value
      )
    ) {
      setError(iata, 'This IATA code is already used');
      ok = false;
    }

    if (!ok) showToast('Please fix the highlighted fields.', 'error');
    return ok;
  }

  /* ---------- Create / Update / Delete / Toggle ---------- */
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    if (!validate()) return;

    const id = $('#airlineId').value;
    const payload = {
      name: $('#airlineName').value.trim(),
      iata: $('#airlineIata').value.trim().toUpperCase(),
      description: $('#airlineDesc').value.trim(),
      status: $('#airlineStatus').value,
      logo: logoData || ''
    };

    if (id) {
      const a = airlines.find((x) => String(x.id) === id);
      Object.assign(a, payload);
      showToast(`${payload.name} updated.`, 'success');
    } else {
      payload.id = airlines.length ? Math.max(...airlines.map((a) => a.id)) + 1 : 1;
      airlines.push(payload);
      showToast(`${payload.name} added.`, 'success');
    }

    save();
    closeModal();
    render();
  });

  $('#airlineTableBody').addEventListener('click', (e) => {
    const editBtn = e.target.closest('[data-edit]');
    const toggleBtn = e.target.closest('[data-toggle]');
    const deleteBtn = e.target.closest('[data-delete]');

    if (editBtn) {
      const a = airlines.find((x) => String(x.id) === editBtn.dataset.edit);
      if (a) openModal(a);
    }

    if (toggleBtn) {
      const a = airlines.find((x) => String(x.id) === toggleBtn.dataset.toggle);
      if (a) {
        a.status = a.status === 'Active' ? 'Inactive' : 'Active';
        save();
        render();
        showToast(`${a.name} is now ${a.status.toLowerCase()}.`, 'success');
      }
    }

    if (deleteBtn) {
      const a = airlines.find((x) => String(x.id) === deleteBtn.dataset.delete);
      if (a) deleteAirline(a);
    }
  });

  /* Flights reference a carrier by id, so deleting an airline that still operates
     flights would dangle those references. In real mode the API refuses it with 409
     AIRLINE_HAS_FLIGHTS — the same guard `Service/AirlineService` applies — so without
     this pre-check the admin would get an error toast for an action the page had
     suggested was fine. This is the check admin-destinations.js already makes for
     routes, worded the same way on purpose so both pages give one piece of advice:
     disable it instead. (Reads through the API layer, item 16.) */
  function flightsUsing(airlineId) {
    return apiGet('/api/admin/flights')
      .then((resp) => {
        const flights = Array.isArray(resp.flights) ? resp.flights : [];
        return flights.filter((f) => f && String(f.airlineId) === String(airlineId));
      })
      // Fail open, like the destinations page: if the flights read fails the delete
      // proceeds and the real API's 409 still stops it — the cost is a less helpful
      // message, not an orphaned flight.
      .catch(() => []);
  }

  function deleteAirline(a) {
    flightsUsing(a.id).then((inUse) => {
      if (inUse.length) {
        showToast(
          `${a.name} (${a.iata}) is used by ${inUse.length} flight${inUse.length === 1 ? '' : 's'} — disable it instead.`,
          'error'
        );
        return;
      }

      if (confirm(`Delete "${a.name}"? This cannot be undone in the demo.`)) {
        airlines = airlines.filter((x) => String(x.id) !== String(a.id));
        save();
        render();
        showToast(`${a.name} deleted.`, 'success');
      }
    });
  }

  /* ---------- Utilities ---------- */
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* ---------- First render (API read — item 16) ---------- */
  apiGet('/api/airlines')
    .then((resp) => {
      airlines = Array.isArray(resp.airlines) && resp.airlines.length ? resp.airlines : load();
      render();
    })
    .catch(() => {
      airlines = load(); // mock fallback (also seeds + runs the RM migration)
      render();
    });
});
