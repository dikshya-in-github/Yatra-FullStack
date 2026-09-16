/* =========================================
   YATRA ADMIN — FLIGHTS PAGE JS
   CRUD (Master Plan §2.1 #4). Reads go through
   the API layer (item 16 — GET /api/admin/flights);
   writes stay in localStorage until the Spring
   API replaces them in Phase 5 (same data shapes
   either way, per the dynamic-readiness rule).

   Key rules baked in:
   - The airline dropdown is fed from the AIRLINES
     store (via GET /api/airlines here) — flights
     always reference an airline by id, never
     free text.
   - Seat Capacity is the ONLY seat input. Available
     seats = capacity − booked, calculated for display;
     there is no manual available-seats field
     (Master Plan §2.6, teacher-flagged rule).
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const STORAGE_KEY = 'yatra_admin_flights';
  const AIRLINE_KEY = 'yatra_admin_airlines'; // read-only here — owned by admin-airlines.js
  const PAGE_SIZE = 8;

  /* ---------- Nepali domestic airports (matches searchFlight.js CITY map) ---------- */
  const CITIES = {
    KTM: 'Kathmandu', PKR: 'Pokhara', BWA: 'Bhairahawa', BDP: 'Bhadrapur',
    BIR: 'Biratnagar', BHR: 'Bharatpur', JKR: 'Janakpur', SIM: 'Simara',
    DHI: 'Dhangadhi', KEP: 'Nepalgunj', TMI: 'Tumlingtar'
  };

  /* ---------- Fallback airlines (used only if the airlines store is empty) ---------- */
  const FALLBACK_AIRLINES = [
    { id: 1, name: 'Buddha Air', iata: 'U4', logo: 'assets/imgs/airline-buddha.jpg' },
    { id: 2, name: 'Yeti Airlines', iata: 'YT', logo: 'assets/imgs/airline-yeti.jpg' },
    { id: 3, name: 'Shree Airlines', iata: 'S3', logo: 'assets/imgs/airline-shree.svg' },
    { id: 4, name: 'Sita Air', iata: 'ST', logo: 'assets/imgs/airline-sita.jpeg' }
  ];

  /* ---------- Seed flights (4 real Nepali carriers, per project scope) ---------- */
  const SEED_FLIGHTS = [
    {
      id: 1, no: 'U4 951', airlineId: 1, from: 'KTM', to: 'PKR',
      dep: '06:50', arr: '07:35', aircraft: 'ATR 72', fare: 8299.99,
      seats: 70, bookedSeats: 4, status: 'Active'
    },
    {
      id: 2, no: 'YT 958', airlineId: 2, from: 'KTM', to: 'PKR',
      dep: '09:55', arr: '10:40', aircraft: 'ATR 72', fare: 8449.99,
      seats: 70, bookedSeats: 11, status: 'Active'
    },
    {
      id: 3, no: 'S3 507', airlineId: 3, from: 'KTM', to: 'BIR',
      dep: '11:35', arr: '12:20', aircraft: 'CRJ 700', fare: 8899.99,
      seats: 78, bookedSeats: 6, status: 'Active'
    },
    {
      id: 4, no: 'ST 221', airlineId: 4, from: 'KTM', to: 'KEP',
      dep: '12:50', arr: '13:45', aircraft: 'Dornier 228', fare: 9199.99,
      seats: 19, bookedSeats: 2, status: 'Active'
    },
    {
      id: 5, no: 'U4 953', airlineId: 1, from: 'KTM', to: 'BWA',
      dep: '15:10', arr: '16:00', aircraft: 'ATR 72', fare: 8799.99,
      seats: 70, bookedSeats: 0, status: 'Active'
    },
    {
      id: 6, no: 'YT 962', airlineId: 2, from: 'PKR', to: 'KTM',
      dep: '17:25', arr: '18:10', aircraft: 'ATR 42', fare: 8299.99,
      seats: 46, bookedSeats: 9, status: 'Active'
    }
  ];

  /* ---------- State (mock "repository") ---------- */
  let flights = [];     // initial read goes through the API layer (item 16)
  let airlines = [];    // read via GET /api/airlines (item 16)
  let state = { search: '', airline: 'ALL', status: 'ALL', page: 1 };

  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) return JSON.parse(raw);
    } catch (err) { /* corrupted storage → reseed */ }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(SEED_FLIGHTS));
    return [...SEED_FLIGHTS];
  }

  function save() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(flights));
  }

  /* Airlines are OWNED by admin-airlines.js — this page only reads them
     (via GET /api/airlines, item 16). Falls back to the 4 built-in
     carriers if that store/route returns empty. */
  function loadAirlines() {
    try {
      const raw = localStorage.getItem(AIRLINE_KEY);
      const data = raw ? JSON.parse(raw) : null;
      if (Array.isArray(data) && data.length) return data;
    } catch (err) { /* fall through */ }
    return [...FALLBACK_AIRLINES];
  }

  function airlineById(id) {
    return airlines.find((a) => String(a.id) === String(id)) || null;
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
    return flights.filter((f) => {
      const al = airlineById(f.airlineId);
      const airlineName = al ? al.name.toLowerCase() : '';
      const route = `${f.from} ${f.to}`.toLowerCase();
      const matchesQ =
        !q ||
        f.no.toLowerCase().includes(q) ||
        route.includes(q) ||
        airlineName.includes(q);
      const matchesAirline =
        state.airline === 'ALL' || String(f.airlineId) === state.airline;
      const matchesStatus =
        state.status === 'ALL' || f.status === state.status;
      return matchesQ && matchesAirline && matchesStatus;
    });
  }

  const totalPages = () => Math.max(1, Math.ceil(filtered().length / PAGE_SIZE));

  function clampPage() {
    state.page = Math.min(Math.max(1, state.page), totalPages());
  }

  /* ---------- Render helpers ---------- */
  function npr(n) {
    return 'NPR ' + Number(n).toLocaleString('en-US', {
      minimumFractionDigits: 2, maximumFractionDigits: 2
    });
  }

  function logoChipHtml(al) {
    if (!al) return '<span class="logo-chip"><span class="logo-fallback">?</span></span>';
    if (al.logo && !al.logo.startsWith('data:')) {
      return `<span class="logo-chip"><img src="${al.logo}" alt="${escapeHtml(al.name)} logo"
        onerror="this.style.display='none'; this.nextElementSibling.style.display='flex';">
        <span class="logo-fallback" style="display:none;">${escapeHtml(al.iata)}</span></span>`;
    }
    if (al.logo && al.logo.startsWith('data:')) {
      return `<span class="logo-chip"><img src="${al.logo}" alt="${escapeHtml(al.name)} logo"></span>`;
    }
    return `<span class="logo-chip"><span class="logo-fallback">${escapeHtml(al.iata)}</span></span>`;
  }

  /* ---------- Table render ---------- */
  function render() {
    clampPage();
    const rows = filtered();
    const start = (state.page - 1) * PAGE_SIZE;
    const pageRows = rows.slice(start, start + PAGE_SIZE);

    const tbody = $('#flightTableBody');
    tbody.innerHTML = pageRows
      .map((f) => {
        const al = airlineById(f.airlineId);
        const available = f.seats - f.bookedSeats;
        return `
      <tr>
        <td><strong>${escapeHtml(f.no)}</strong></td>
        <td>
          <div class="al-cell">
            ${logoChipHtml(al)}
            <span>${al ? escapeHtml(al.name) : '<em>Unknown airline</em>'}</span>
          </div>
        </td>
        <td>
          <strong>${escapeHtml(f.from)} → ${escapeHtml(f.to)}</strong>
          <span class="cell-sub">${escapeHtml((CITIES[f.from] || f.from) + ' → ' + (CITIES[f.to] || f.to))}</span>
        </td>
        <td><strong>${escapeHtml(f.dep)} → ${escapeHtml(f.arr)}</strong></td>
        <td>${escapeHtml(f.aircraft || '—')}</td>
        <td><strong>${npr(f.fare)}</strong></td>
        <td>
          <strong class="${available === 0 ? 'seat-out' : ''}">${available}</strong>
          <span class="cell-sub">of ${f.seats} · ${f.bookedSeats} booked</span>
        </td>
        <td><span class="badge ${f.status === 'Active' ? 'badge-success' : 'badge-neutral'}">${f.status}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-edit="${f.id}" aria-label="Edit ${escapeHtml(f.no)}"><i class="fa-solid fa-pen"></i></button>
            <button type="button" class="icon-btn" data-toggle="${f.id}" aria-label="${f.status === 'Active' ? 'Disable' : 'Activate'} ${escapeHtml(f.no)}"><i class="fa-solid fa-${f.status === 'Active' ? 'power-off' : 'rotate-left'}"></i></button>
            <button type="button" class="icon-btn icon-btn-danger" data-delete="${f.id}" aria-label="Delete ${escapeHtml(f.no)}"><i class="fa-solid fa-trash"></i></button>
          </div>
        </td>
      </tr>`;
      })
      .join('');

    $('#emptyState').hidden = rows.length > 0;
    $('#resultCount').textContent = `${rows.length} flight${rows.length === 1 ? '' : 's'}`;
    renderPagination(rows.length);
  }

  function renderPagination(totalRows) {
    const pages = totalPages();
    $('#pageInfo').textContent = totalRows
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, totalRows)}–${Math.min(state.page * PAGE_SIZE, totalRows)} of ${totalRows} flights`
      : 'No flights';

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

  /* ---------- Dropdowns (airline store + airports) ---------- */
  function populateAirlineFilter() {
    const sel = $('#airlineFilter');
    sel.innerHTML =
      '<option value="ALL">All airlines</option>' +
      airlines
        .map((a) => `<option value="${a.id}">${escapeHtml(a.name)}</option>`)
        .join('');
  }

  function populateRouteSelects(selFrom, selTo) {
    const options = Object.entries(CITIES)
      .map(([code, city]) => `<option value="${code}">${escapeHtml(city)} (${code})</option>`)
      .join('');
    selFrom.innerHTML = '<option value="">Select origin…</option>' + options;
    selTo.innerHTML = '<option value="">Select destination…</option>' + options;
  }

  function populateAirlineSelect(selectedId) {
    const sel = $('#flightAirline');
    if (!airlines.length) {
      sel.innerHTML = '<option value="">No airlines — add one in Airlines first</option>';
      return;
    }
    // All airlines are listed; inactive ones are suffixed so the edit
    // modal never loses its stored value.
    sel.innerHTML =
      '<option value="">Select airline…</option>' +
      airlines
        .map(
          (a) =>
            `<option value="${a.id}">${escapeHtml(a.name)} (${escapeHtml(a.iata)})${a.status === 'Inactive' ? ' — Inactive' : ''}</option>`
        )
        .join('');
    if (selectedId) sel.value = String(selectedId);
  }

  /* ---------- Toolbar events ---------- */
  $('#flightSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    render();
  });

  $('#airlineFilter').addEventListener('change', (e) => {
    state.airline = e.target.value;
    state.page = 1;
    render();
  });

  $('#statusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
    state.page = 1;
    render();
  });

  /* ---------- Modal open/close ---------- */
  const modal = $('#flightModal');
  const form = $('#flightForm');

  function openModal(flight = null) {
    form.reset();
    clearErrors();
    populateRouteSelects($('#flightFrom'), $('#flightTo'));
    populateAirlineSelect(flight ? flight.airlineId : null);

    $('#flightId').value = flight ? flight.id : '';
    $('#flightNo').value = flight ? flight.no : '';
    $('#flightFrom').value = flight ? flight.from : '';
    $('#flightTo').value = flight ? flight.to : '';
    $('#flightDep').value = flight ? flight.dep : '';
    $('#flightArr').value = flight ? flight.arr : '';
    $('#flightAircraft').value = flight ? flight.aircraft || '' : '';
    $('#flightFare').value = flight ? flight.fare : '';
    $('#flightSeats').value = flight ? flight.seats : '';
    $('#flightStatus').value = flight ? flight.status : 'Active';
    $('#modalTitle').textContent = flight ? 'Edit Flight' : 'Add Flight';

    modal.hidden = false;
    $('#flightNo').focus({ preventScroll: true });
  }

  function closeModal() {
    modal.hidden = true;
  }

  $('#addFlightBtn').addEventListener('click', () => openModal());
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalCancel').addEventListener('click', closeModal);
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !modal.hidden) closeModal();
  });

  /* Convenience: picking an airline pre-fills its code in an empty flight no */
  $('#flightAirline').addEventListener('change', (e) => {
    const al = airlineById(e.target.value);
    if (al && !$('#flightNo').value.trim()) {
      $('#flightNo').value = al.iata + ' ';
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

    const no = $('#flightNo');
    const airlineSel = $('#flightAirline');
    const fromSel = $('#flightFrom');
    const toSel = $('#flightTo');
    const dep = $('#flightDep');
    const arr = $('#flightArr');
    const fare = $('#flightFare');
    const seats = $('#flightSeats');

    const noNorm = no.value.trim().toUpperCase().replace(/\s+/g, ' ');
    if (!noNorm) {
      setError(no, 'Flight number is required');
      ok = false;
    } else if (!/^[A-Z0-9]{2,3} \d{1,4}[A-Z]?$/.test(noNorm)) {
      setError(no, 'Use the format "U4 951" — airline code + number');
      ok = false;
    } else if (
      flights.some(
        (f) => f.no.toUpperCase().replace(/\s+/g, ' ') === noNorm && String(f.id) !== $('#flightId').value
      )
    ) {
      setError(no, 'This flight number already exists');
      ok = false;
    }

    if (!airlineSel.value) {
      setError(airlineSel, airlines.length
        ? 'Select the operating airline'
        : 'No airlines exist — add one on the Airlines page first');
      ok = false;
    }

    if (!fromSel.value) {
      setError(fromSel, 'Select the origin airport');
      ok = false;
    }
    if (!toSel.value) {
      setError(toSel, 'Select the destination airport');
      ok = false;
    }
    if (fromSel.value && toSel.value && fromSel.value === toSel.value) {
      setError(toSel, 'Origin and destination cannot be the same');
      ok = false;
    }

    if (!dep.value) {
      setError(dep, 'Departure time is required');
      ok = false;
    }
    if (!arr.value) {
      setError(arr, 'Arrival time is required');
      ok = false;
    }
    if (dep.value && arr.value && arr <= dep) {
      setError(arr, 'Arrival must be after departure (same-day domestic flights)');
      ok = false;
    }

    if (fare.value.trim() === '' || isNaN(Number(fare.value)) || Number(fare.value) <= 0) {
      setError(fare, 'Base fare must be a positive amount');
      ok = false;
    }

    const seatsNum = Number(seats.value);
    if (!Number.isInteger(seatsNum) || seatsNum < 1 || seatsNum > 999) {
      setError(seats, 'Seat capacity must be a whole number between 1 and 999');
      ok = false;
    }

    if (!ok) toast('Please fix the highlighted fields.', 'error');
    return ok;
  }

  /* ---------- Create / Update / Delete / Toggle ---------- */
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    if (!validate()) return;

    const id = $('#flightId').value;
    const payload = {
      no: $('#flightNo').value.trim().toUpperCase().replace(/\s+/g, ' '),
      airlineId: Number($('#flightAirline').value),
      from: $('#flightFrom').value,
      to: $('#flightTo').value,
      dep: $('#flightDep').value,
      arr: $('#flightArr').value,
      aircraft: $('#flightAircraft').value.trim(),
      fare: Number($('#flightFare').value),
      seats: Number($('#flightSeats').value),
      status: $('#flightStatus').value
    };

    if (id) {
      const f = flights.find((x) => String(x.id) === id);
      // bookedSeats is system data (from bookings) — preserved, never edited here.
      Object.assign(f, payload);
      toast(`${payload.no} updated.`, 'success');
    } else {
      payload.id = flights.length ? Math.max(...flights.map((f) => f.id)) + 1 : 1;
      payload.bookedSeats = 0; // new flights start with no bookings
      flights.push(payload);
      toast(`${payload.no} added.`, 'success');
    }

    save();
    closeModal();
    render();
  });

  $('#flightTableBody').addEventListener('click', (e) => {
    const editBtn = e.target.closest('[data-edit]');
    const toggleBtn = e.target.closest('[data-toggle]');
    const deleteBtn = e.target.closest('[data-delete]');

    if (editBtn) {
      const f = flights.find((x) => String(x.id) === editBtn.dataset.edit);
      if (f) openModal(f);
    }

    if (toggleBtn) {
      const f = flights.find((x) => String(x.id) === toggleBtn.dataset.toggle);
      if (f) {
        f.status = f.status === 'Active' ? 'Inactive' : 'Active';
        save();
        render();
        toast(`${f.no} is now ${f.status.toLowerCase()}.`, 'success');
      }
    }

    if (deleteBtn) {
      const f = flights.find((x) => String(x.id) === deleteBtn.dataset.delete);
      if (f && confirm(`Delete flight "${f.no}"? This cannot be undone in the demo.`)) {
        flights = flights.filter((x) => String(x.id) !== deleteBtn.dataset.delete);
        save();
        render();
        toast(`${f.no} deleted.`, 'success');
      }
    }
  });

  /* ---------- Utilities ---------- */
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* ---------- First render (API reads — item 16) ---------- */
  Promise.all([
    apiGet('/api/admin/flights'),
    apiGet('/api/airlines')
  ]).then(([flResp, alResp]) => {
    flights = Array.isArray(flResp.flights) && flResp.flights.length ? flResp.flights : load();
    airlines = Array.isArray(alResp.airlines) && alResp.airlines.length ? alResp.airlines : loadAirlines();
    populateAirlineFilter();
    render();
  }).catch(() => {
    flights = load();     // mock fallbacks (also seed)
    airlines = loadAirlines();
    populateAirlineFilter();
    render();
  });
});
