/* =========================================
   YATRA ADMIN — DESTINATIONS PAGE JS
   Destination CRUD (Master Plan §2.1 #7).
   Data lives in localStorage
   `yatra_admin_destinations` until the Spring
   API replaces it.

   Image handling mirrors the §3.5 hybrid
   split from the ADMIN side:
   - destinations store a URL (mocking the
     Cloudinary `secure_url` the backend will
     persist) — unlike airline logos, which
     are DB Base64/BLOB.
   - the demo upload converts to a data URL
     so the demo works offline; the comment
     says what the backend does instead.

   Rules baked in:
   - Airport codes must match searchFlight.js's
     11-airport CITY map (KTM PKR BWA BDP BIR
     BHR JKR SIM DHI KEP TMI) — the storefront
     search + admin-flights route dropdowns
     are built on it. A code outside the map
     would silently break search.
   - Deleting an airport that flights still
     reference is blocked with a helpful
     error (disable instead) — same spirit as
     admin-flights keeping inactive airlines
     selectable so stored routes never dangle.
   - Reads go through the API layer (item 16 —
     GET /api/admin/destinations); writes stay
     local until the Phase 8 swap, and the demo
     upload swaps for CloudinaryService.upload().
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const STORAGE_KEY = 'yatra_admin_destinations';
  const PAGE_SIZE = 8;

  /* The 11-airport map every other page uses (searchFlight.js, admin-flights.js) */
  const KNOWN_CODES = ['KTM', 'PKR', 'BWA', 'BDP', 'BIR', 'BHR', 'JKR', 'SIM', 'DHI', 'KEP', 'TMI'];

  /* ---------- Seed data (self-seeds on first open; mirrors searchFlight.js) ---------- */
  const SEED_DESTINATIONS = [
    {
      id: 1, city: 'Kathmandu', code: 'KTM', airport: 'Tribhuvan International Airport',
      description: "Nepal's capital and the hub of the domestic network — nearly every route connects here.",
      imageUrl: 'assets/imgs/header-top-image.jpg', status: 'Active'
    },
    {
      id: 2, city: 'Pokhara', code: 'PKR', airport: 'Pokhara International Airport',
      description: 'Gateway to the Annapurnas and Phewa Lake — the busiest tourist route in the country.',
      imageUrl: 'assets/imgs/pokhara.jpg', status: 'Active'
    },
    {
      id: 3, city: 'Biratnagar', code: 'BIR', airport: 'Biratnagar Airport',
      description: 'Eastern metropolis and industrial hub of the Terai plains.',
      imageUrl: 'assets/imgs/biratnagar.png', status: 'Active'
    },
    {
      id: 4, city: 'Bhairahawa', code: 'BWA', airport: 'Gautam Buddha International Airport',
      description: 'Home of Lumbini, the birthplace of Buddha — the newest international gateway.',
      imageUrl: 'assets/imgs/bhairahawa.jpeg', status: 'Active'
    },
    {
      id: 5, city: 'Nepalgunj', code: 'KEP', airport: 'Nepalgunj Airport',
      description: 'Western Terai hub and the jumping-off point for remote western Nepal.',
      imageUrl: 'assets/imgs/nepalgunj.jpeg', status: 'Active'
    },
    {
      id: 6, city: 'Bharatpur', code: 'BHR', airport: 'Bharatpur Airport',
      description: 'Chitwan city serving Sauraha and the national-park safari circuit.',
      imageUrl: 'assets/imgs/mountainview.jpg', status: 'Active'
    },
    {
      id: 7, city: 'Bhadrapur', code: 'BDP', airport: 'Bhadrapur Airport',
      description: "Eastern gateway to Ilam's tea gardens and the taplejung trailheads.",
      imageUrl: '', status: 'Active'
    },
    {
      id: 8, city: 'Janakpur', code: 'JKR', airport: 'Janakpur Airport',
      description: 'Historic Mithila city and pilgrimage centre of Sita Janaki.',
      imageUrl: '', status: 'Active'
    },
    {
      id: 9, city: 'Simara', code: 'SIM', airport: 'Simara Airport',
      description: 'Bara district airport serving the industrial corridor south of Kathmandu.',
      imageUrl: '', status: 'Active'
    },
    {
      id: 10, city: 'Dhangadhi', code: 'DHI', airport: 'Dhangadhi Airport',
      description: 'Far-western commercial hub and gateway to Sudurpashchim.',
      imageUrl: '', status: 'Active'
    },
    {
      id: 11, city: 'Tumlingtar', code: 'TMI', airport: 'Tumlingtar Airport',
      description: 'Eastern hill-town airport on the Arun valley route.',
      imageUrl: '', status: 'Active'
    }
  ];

  /* ---------- State (mock "repository") ---------- */
  let destinations = []; // initial read goes through the API layer (item 16)
  let state = { search: '', status: 'ALL', page: 1 };
  let confirmAction = null; // queue for the styled confirm modal
  let imgData = null; // staged data URL from the demo upload

  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const data = JSON.parse(raw);
        if (Array.isArray(data)) return data;
      }
    } catch (err) { /* corrupted storage → reseed */ }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(SEED_DESTINATIONS));
    return [...SEED_DESTINATIONS];
  }

  function save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(destinations));
    } catch (err) {
      toast('Could not save — storage quota reached.', 'error');
    }
  }

  /* Older/edge records may lack fields — normalize for display. */
  function normalize(d) {
    if (!d || typeof d !== 'object') return;
    if (!d.id) d.id = Date.now();
    if (!d.city) d.city = 'Unnamed';
    if (!d.code) d.code = '—';
    if (!d.airport) d.airport = '—';
    if (!d.status) d.status = 'Active';
    if (typeof d.imageUrl !== 'string') d.imageUrl = '';
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
    return destinations.filter((d) => {
      const matchesQ =
        !q ||
        d.city.toLowerCase().includes(q) ||
        d.airport.toLowerCase().includes(q) ||
        d.code.toLowerCase().includes(q);
      const matchesStatus = state.status === 'ALL' || d.status === state.status;
      return matchesQ && matchesStatus;
    });
  }

  const totalPages = () => Math.max(1, Math.ceil(filtered().length / PAGE_SIZE));

  function clampPage() {
    state.page = Math.min(Math.max(1, state.page), totalPages());
  }

  /* ---------- Formatting helpers ---------- */
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  const statusBadge = (s) => (s === 'Active' ? 'badge-success' : 'badge-neutral');

  /* ---------- Table render ---------- */
  function render() {
    clampPage();
    const rows = filtered();
    const start = (state.page - 1) * PAGE_SIZE;
    const pageRows = rows.slice(start, start + PAGE_SIZE);
    pageRows.forEach(normalize);

    const tbody = $('#destTableBody');
    tbody.innerHTML = pageRows
      .map((d) => `
      <tr>
        <td>${imgChip(d)}</td>
        <td><strong>${escapeHtml(d.city)}</strong></td>
        <td>${escapeHtml(d.airport)}</td>
        <td><span class="badge badge-neutral">${escapeHtml(d.code)}</span></td>
        <td class="desc-cell">${escapeHtml(d.description || '—')}</td>
        <td><span class="badge ${statusBadge(d.status)}">${escapeHtml(d.status)}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-edit="${d.id}" aria-label="Edit ${escapeHtml(d.city)}"><i class="fa-solid fa-pen"></i></button>
            <button type="button" class="icon-btn" data-toggle="${d.id}" aria-label="${d.status === 'Active' ? 'Disable' : 'Activate'} ${escapeHtml(d.city)}"><i class="fa-solid fa-${d.status === 'Active' ? 'power-off' : 'rotate-left'}"></i></button>
            <button type="button" class="icon-btn icon-btn-danger" data-delete="${d.id}" aria-label="Delete ${escapeHtml(d.city)}"><i class="fa-solid fa-trash"></i></button>
          </div>
        </td>
      </tr>`)
      .join('');

    $('#emptyState').hidden = rows.length > 0;
    $('#resultCount').textContent = `${rows.length} destination${rows.length === 1 ? '' : 's'}`;
    renderPagination(rows.length);
  }

  /* Image chip: URL image with code-badge fallback (same pattern as airline logos) */
  function imgChip(d) {
    if (d.imageUrl) {
      return `<span class="logo-chip"><img src="${escapeHtml(d.imageUrl)}" alt="${escapeHtml(d.city)}"
        onerror="this.style.display='none'; this.nextElementSibling.style.display='flex';">
        <span class="logo-fallback" style="display:none;">${escapeHtml(d.code)}</span></span>`;
    }
    return `<span class="logo-chip"><span class="logo-fallback">${escapeHtml(d.code)}</span></span>`;
  }

  function renderPagination(totalRows) {
    const pages = totalPages();
    $('#pageInfo').textContent = totalRows
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(state.page * PAGE_SIZE, totalRows)} of ${totalRows} destinations`
      : 'No destinations';

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
  $('#destSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
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
  const modal = $('#destModal');
  const form = $('#destForm');

  function openModal(dest = null) {
    form.reset();
    clearErrors();
    imgData = null;
    $('#destId').value = dest ? dest.id : '';
    $('#destCity').value = dest ? dest.city : '';
    $('#destCode').value = dest ? dest.code : '';
    $('#destAirport').value = dest ? dest.airport : '';
    $('#destDesc').value = dest ? dest.description || '' : '';
    $('#destImageUrl').value = dest ? dest.imageUrl || '' : '';
    $('#destStatus').value = dest ? dest.status : 'Active';
    $('#modalTitle').textContent = dest ? 'Edit Destination' : 'Add Destination';
    renderImgPreview();
    modal.hidden = false;
    $('#destCity').focus({ preventScroll: true });
  }

  function closeModal() {
    modal.hidden = true;
    imgData = null;
  }

  $('#addDestBtn').addEventListener('click', () => openModal());
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

  /* ---------- Demo image upload (data URL stands in for Cloudinary) ---------- */
  const imgInput = $('#imgInput');

  $('#uploadImgBtn').addEventListener('click', () => imgInput.click());

  imgInput.addEventListener('change', () => {
    const file = imgInput.files && imgInput.files[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      toast('Image must be under 2 MB (demo limit).', 'error');
      imgInput.value = '';
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      imgData = reader.result; // demo stand-in for a Cloudinary secure_url
      renderImgPreview();
      toast('Image ready — the backend will swap this for a Cloudinary URL.', 'success');
    };
    reader.onerror = () => toast('Could not read that file.', 'error');
    reader.readAsDataURL(file);
  });

  $('#removeImgBtn').addEventListener('click', () => {
    imgData = null;
    imgInput.value = '';
    renderImgPreview();
  });

  function renderImgPreview() {
    const preview = $('#imgPreview');
    const removeBtn = $('#removeImgBtn');
    if (imgData) {
      preview.innerHTML = `<img src="${imgData}" alt="Image preview">`;
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

    const city = $('#destCity');
    const code = $('#destCode');
    const airport = $('#destAirport');

    if (!city.value.trim()) {
      setError(city, 'City name is required');
      ok = false;
    } else if (
      destinations.some(
        (d) =>
          d.city.toLowerCase() === city.value.trim().toLowerCase() &&
          String(d.id) !== $('#destId').value
      )
    ) {
      setError(city, 'A destination with this city name already exists');
      ok = false;
    }

    const codeVal = code.value.trim().toUpperCase();
    if (!codeVal) {
      setError(code, 'Airport code is required');
      ok = false;
    } else if (!/^[A-Z]{3}$/.test(codeVal)) {
      setError(code, 'Airport code must be exactly 3 letters (e.g. PKR)');
      ok = false;
    } else if (
      destinations.some(
        (d) =>
          d.code.toUpperCase() === codeVal && String(d.id) !== $('#destId').value
      )
    ) {
      setError(code, 'This airport code is already used');
      ok = false;
    } else if (!KNOWN_CODES.includes(codeVal)) {
      setError(code, 'Code must match searchFlight\'s airport map (KTM PKR BWA BDP BIR BHR JKR SIM DHI KEP TMI)');
      ok = false;
    }

    if (!airport.value.trim()) {
      setError(airport, 'Airport name is required');
      ok = false;
    }

    if (!ok) toast('Please fix the highlighted fields.', 'error');
    return ok;
  }

  /* ---------- Create / Update / Delete / Toggle ---------- */
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    if (!validate()) return;

    const id = $('#destId').value;
    const payload = {
      city: $('#destCity').value.trim(),
      code: $('#destCode').value.trim().toUpperCase(),
      airport: $('#destAirport').value.trim(),
      description: $('#destDesc').value.trim(),
      // Demo upload (data URL) wins over the URL field; both are "URL-shaped"
      // values in the same column the backend will keep a Cloudinary URL in.
      imageUrl: imgData || $('#destImageUrl').value.trim(),
      status: $('#destStatus').value
    };

    if (id) {
      const d = destinations.find((x) => String(x.id) === id);
      Object.assign(d, payload);
      toast(`${payload.city} updated.`, 'success');
    } else {
      payload.id = destinations.length ? Math.max(...destinations.map((d) => d.id)) + 1 : 1;
      destinations.push(payload);
      toast(`${payload.city} added.`, 'success');
    }

    save();
    closeModal();
    render();
  });

  /* Flights reference routes by code — deleting an airport in use would
     dangle those references. Block it and point at Disable instead.
     (Reads through the API layer, item 16.) */
  function flightsUsing(code) {
    return apiGet('/api/admin/flights')
      .then((resp) => {
        const flights = Array.isArray(resp.flights) ? resp.flights : [];
        return flights.filter((f) => f && (f.from === code || f.to === code));
      })
      .catch(() => []);
  }

  function deleteDestination(d) {
    flightsUsing(d.code).then((inUse) => {
    if (inUse.length) {
      toast(
        `${d.city} (${d.code}) is used by ${inUse.length} flight${inUse.length === 1 ? '' : 's'} — disable it instead.`,
        'error'
      );
      return;
    }
    askConfirm({
      title: 'Delete this destination?',
      message: `${d.city} (${d.code}) — ${d.airport} will be removed permanently. The customer search and admin-flights dropdowns rebuild from this store.`,
      yesLabel: 'Delete destination',
      icon: 'trash',
      onYes: () => {
        destinations = destinations.filter((x) => x.id !== d.id);
        save();
        render();
        toast(`${d.city} deleted.`, 'success');
      }
    });
    });
  }

  function toggleDestination(d) {
    d.status = d.status === 'Active' ? 'Inactive' : 'Active';
    save();
    render();
    toast(`${d.city} is now ${d.status.toLowerCase()}.`, 'success');
  }

  /* ---------- Row action wiring (event delegation) ---------- */
  $('#destTableBody').addEventListener('click', (e) => {
    const editBtn = e.target.closest('[data-edit]');
    const toggleBtn = e.target.closest('[data-toggle]');
    const deleteBtn = e.target.closest('[data-delete]');

    if (editBtn) {
      const d = destinations.find((x) => String(x.id) === editBtn.dataset.edit);
      if (d) openModal(d);
    }
    if (toggleBtn) {
      const d = destinations.find((x) => String(x.id) === toggleBtn.dataset.toggle);
      if (d) toggleDestination(d);
    }
    if (deleteBtn) {
      const d = destinations.find((x) => String(x.id) === deleteBtn.dataset.delete);
      if (d) deleteDestination(d);
    }
  });

  /* ---------- First render (API read — item 16) ---------- */
  apiGet('/api/admin/destinations')
    .then((resp) => {
      destinations = Array.isArray(resp.destinations) && resp.destinations.length ? resp.destinations : load();
      render();
    })
    .catch(() => {
      destinations = load(); // mock fallback (also seeds)
      render();
    });
});
