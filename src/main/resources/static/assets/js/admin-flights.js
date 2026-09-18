/* =========================================
   YATRA ADMIN — FLIGHTS PAGE JS
   CRUD (Master Plan §2.1 #4). Full backend wiring as of the
   fix-plan §12/§13 execution step 5 (2026-09-18) — this page is the REFERENCE
   PATTERN every other admin module is migrated to follow.

   Reads  : GET /api/admin/flights, and GET /api/airlines +
            GET /api/destinations for the two dropdown groups.
   Writes : POST /api/admin/flights  (create)
            PUT /api/admin/flights/{id}  (edit, and the status toggle)
            DELETE /api/admin/flights/{id}  (delete)
   All of them through api.js, so this file contains no
   localStorage access and no seed rows of its own. It used to
   own both (`STORAGE_KEY`, `SEED_FLIGHTS`, `load()`, `save()`),
   which meant a Save wrote to the browser and never reached the
   database.

   Key rules baked in:
   - Both dropdown groups are fed from real backend lists, and
     flights always reference an airline by id, never free text.
   - Seat Capacity is the ONLY seat input. Available
     seats = capacity − booked, calculated for display;
     there is no manual available-seats field
     (Master Plan §2.6, teacher-flagged rule).
   - After any write the list is re-read from the API, so the
     table can never show a row the database does not hold.
   - A rejected write surfaces the server's own message (e.g. a
     flight with bookings refusing to delete) instead of
     pretending to succeed.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 8;

  /* ---------- Nepali domestic airports ----------
     Offline fallback for a route cell label only: the origin/destination SELECTS
     are built from GET /api/destinations (the rows the API resolves a flight's
     `from`/`to` against), so the form cannot offer an airport that does not
     exist. This map still names a code in the table when the airline/destination
     read fails, which is why it stays. */
  const CITIES = {
    KTM: 'Kathmandu', PKR: 'Pokhara', BWA: 'Bhairahawa', BDP: 'Bhadrapur',
    BIR: 'Biratnagar', BHR: 'Bharatpur', JKR: 'Janakpur', SIM: 'Simara',
    DHI: 'Dhangadhi', KEP: 'Nepalgunj', TMI: 'Tumlingtar'
  };

  /* ---------- State ----------
     Every list below is a backend read: flights from GET /api/admin/flights,
     airlines from GET /api/airlines, destinations from GET /api/destinations.
     The old page-private store (STORAGE_KEY + SEED_FLIGHTS + load()/save()) is
     gone: it is why an Add looked like it worked and never reached MySQL. */
  let flights = [];
  let airlines = [];       // airline filter + the modal's airline select
  let destinations = [];   // the modal's origin / destination selects
  /* `page` is 1-based here (the UI shows "Page 2"), `totalPages`/`totalElements`
     are the server's own counts from the last list call — never recomputed from
     the rows on screen, which are one page of them. */
  let state = { search: '', airline: 'ALL', status: 'ALL', page: 1, totalPages: 1, totalElements: 0 };

  /* A route cell's city name: the destination row is the authority, CITIES is
     the offline fallback for a code the API did not return. */
  function cityName(code) {
    const d = destinations.find(
      (x) => String(x.code).toUpperCase() === String(code).toUpperCase()
    );
    return (d && d.city) || CITIES[code] || code;
  }

  function airlineById(id) {
    return airlines.find((a) => String(a.id) === String(id)) || null;
  }

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page,
     which also creates the #toast element it writes to. */

  /* ---------- Query, not filtering ----------
     The toolbar used to filter this page's own array and paginate it here, which
     meant the table showed a subset the DATABASE never agreed to — a search could
     hit one page of rows and miss the rest. The list call now carries the filters
     and the page number, exactly as GET /api/admin/flights supports them (see the
     repository's searchPage), and MySQL decides the result set and the counts.
     `page` is 1-based in this UI and 0-based on the wire (Spring's convention). */
  function listQuery() {
    const p = new URLSearchParams();
    if (state.search.trim()) p.set('search', state.search.trim());
    if (state.airline !== 'ALL') p.set('airlineId', state.airline);
    if (state.status !== 'ALL') p.set('status', state.status);
    p.set('page', String(Math.max(0, state.page - 1)));
    p.set('size', String(PAGE_SIZE));
    return '/api/admin/flights?' + p.toString();
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

  /* ---------- Table render ----------
     `flights` is the server's page as returned — nothing is sliced, filtered or
     counted in the browser, so an empty table means "the backend matched no
     rows", never "the row was not in the cached array". */
  function render() {
    const tbody = $('#flightTableBody');
    tbody.innerHTML = flights
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
          <span class="cell-sub">${escapeHtml(cityName(f.from) + ' → ' + cityName(f.to))}</span>
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

    $('#emptyState').hidden = flights.length > 0;
    $('#resultCount').textContent = `${state.totalElements} flight${state.totalElements === 1 ? '' : 's'}`;
    renderPagination();
  }

  function renderPagination() {
    const pages = Math.max(1, state.totalPages);
    const total = state.totalElements;
    $('#pageInfo').textContent = total
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, total)}–${Math.min(state.page * PAGE_SIZE, total)} of ${total} flights`
      : 'No flights';

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

  /* ---------- Dropdowns (airline store + airports) ---------- */
  function populateAirlineFilter() {
    const sel = $('#airlineFilter');
    sel.innerHTML =
      '<option value="ALL">All airlines</option>' +
      airlines
        .map((a) => `<option value="${a.id}">${escapeHtml(a.name)}</option>`)
        .join('');

    /* This runs on EVERY read, and rebuilding innerHTML puts a <select> back on
       its first option — which would silently drop the very filter the request
       was just made with, leaving the toolbar disagreeing with the table. So the
       current selection is re-applied; if that airline is gone from the list
       (deleted on the Airlines page), fall back to All airlines. */
    sel.value = state.airline;
    if (sel.selectedIndex === -1) {
      state.airline = 'ALL';
      sel.value = 'ALL';
    }
  }

  /* Origin / Destination come from GET /api/destinations — the very rows the API
     resolves `from`/`to` against, so the form can only offer airports that
     exist. Inactive destinations are still listed, with a suffix, exactly like
     the airline select below: a flight that points at one has to keep its value
     when the edit modal opens. */
  function populateRouteSelects(selFrom, selTo) {
    const options = destinations
      .map((d) => `<option value="${escapeHtml(d.code)}">${escapeHtml(d.city)} (${escapeHtml(d.code)})${d.status === 'Inactive' ? ' — Inactive' : ''}</option>`)
      .join('');

    if (!options) {
      const none = '<option value="">No destinations — add one on the Destinations page first</option>';
      selFrom.innerHTML = none;
      selTo.innerHTML = none;
      return;
    }

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

  /* ---------- Toolbar events ----------
     Each one re-queries the backend (page reset to 1, because page 3 of the old
     result set has nothing to do with page 3 of the new one). Typing is
     debounced so a search fires one request, not one per keystroke. */
  let searchTimer;
  $('#flightSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(reloadOrToast, 250);
  });

  $('#airlineFilter').addEventListener('change', (e) => {
    state.airline = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  $('#statusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
    state.page = 1;
    reloadOrToast();
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

    if (!ok) showToast('Please fix the highlighted fields.', 'error');
    return ok;
  }

  /* ---------- Reads: one path, used on load, on every filter/page change and
     after every write ----------
     GET /api/admin/flights (with the current query) + GET /api/airlines +
     GET /api/destinations, then render. There is deliberately no seed fallback:
     if the API cannot be reached the page says so, rather than drawing rows that
     are not in the database. */
  async function reload() {
    const [flResp, alResp, destResp] = await Promise.all([
      apiGet(listQuery()),
      apiGet('/api/airlines'),
      apiGet('/api/destinations')
    ]);

    flights = Array.isArray(flResp.flights) ? flResp.flights : [];
    airlines = Array.isArray(alResp.airlines) ? alResp.airlines : [];
    destinations = Array.isArray(destResp.destinations) ? destResp.destinations : [];

    const total = Number(flResp.totalElements);
    state.totalElements = Number.isFinite(total) ? total : flights.length;
    const pages = Number(flResp.totalPages);
    state.totalPages = Number.isFinite(pages) && pages > 0
      ? pages
      : Math.max(1, Math.ceil(state.totalElements / PAGE_SIZE));

    populateAirlineFilter();
    render();
  }

  /* A failed read that has nothing on screen (first load): say so plainly. */
  function showLoadFailure(err) {
    flights = [];
    airlines = [];
    destinations = [];
    state.totalElements = 0;
    state.totalPages = 1;
    populateAirlineFilter();
    render();
    showToast('Could not load flights: ' + ((err && err.message) || err), 'error');
  }

  /* A failed read while a table is already displayed (filter, page, refresh):
     keep what is on screen and report the failure. */
  function reloadOrToast() {
    return reload().catch((err) => {
      showToast('Could not load flights: ' + ((err && err.message) || err), 'error');
    });
  }

  function goToPage(page) {
    const last = Math.max(1, state.totalPages);
    state.page = Math.min(Math.max(1, page), last);
    reloadOrToast();
  }

  /* ---------- Create / Update ----------
     A row → the request body FlightRequest expects. `bookedSeats` is
     deliberately absent (it is derived from the seat map, never written), and
     `date` is carried whenever the row has one: FlightService.apply()
     overwrites the stored date with whatever the request carried, so dropping
     it here would erase a scheduled flight's date on every unrelated edit. */
  function toRequest(f, overrides = {}) {
    const body = {
      no: f.no,
      airlineId: f.airlineId,
      from: f.from,
      to: f.to,
      dep: f.dep,
      arr: f.arr,
      aircraft: f.aircraft || '',
      fare: f.fare,
      seats: f.seats,
      status: f.status
    };
    if (f.date) body.date = f.date;
    return { ...body, ...overrides };
  }

  form.addEventListener('submit', async (e) => {
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

    const saveBtn = $('#modalSave');
    if (saveBtn) saveBtn.disabled = true;

    try {
      if (id) {
        await apiPut('/api/admin/flights/' + id, payload);
      } else {
        await apiPost('/api/admin/flights', payload);
      }
    } catch (err) {
      // The modal stays open and the message is the server's own sentence.
      // A 409 names the flight number that clashed, so it also lands on the
      // field — the same inline surface the client-side check uses.
      if (err && err.code === 'FLIGHT_NO_EXISTS') setError($('#flightNo'), err.message);
      if (saveBtn) saveBtn.disabled = false;
      showToast((err && err.message) || 'The flight could not be saved.', 'error');
      return;
    }

    if (saveBtn) saveBtn.disabled = false;
    closeModal();
    showToast(`${payload.no} ${id ? 'updated' : 'added'}.`, 'success');
    await reloadOrToast();
  });

  $('#flightTableBody').addEventListener('click', (e) => {
    const editBtn = e.target.closest('[data-edit]');
    const toggleBtn = e.target.closest('[data-toggle]');
    const deleteBtn = e.target.closest('[data-delete]');

    if (editBtn) {
      const f = flights.find((x) => String(x.id) === editBtn.dataset.edit);
      if (f) openModal(f);
    }

    /* Activate/Disable is the edit write with one field changed — same endpoint,
       so there is no second code path that could drift from it. */
    if (toggleBtn) {
      const f = flights.find((x) => String(x.id) === toggleBtn.dataset.toggle);
      if (!f) return;
      const next = f.status === 'Active' ? 'Inactive' : 'Active';
      toggleBtn.disabled = true;
      apiPut('/api/admin/flights/' + f.id, toRequest(f, { status: next }))
        .then(() => {
          showToast(`${f.no} is now ${next.toLowerCase()}.`, 'success');
          return reload();
        })
        .catch((err) => {
          toggleBtn.disabled = false;
          showToast((err && err.message) || 'The flight could not be updated.', 'error');
        });
    }

    if (deleteBtn) {
      const f = flights.find((x) => String(x.id) === deleteBtn.dataset.delete);
      if (!f) return;
      if (!confirm(`Delete flight "${f.no}"? This cannot be undone.`)) return;

      deleteBtn.disabled = true;
      apiDelete('/api/admin/flights/' + f.id)
        .then(() => {
          showToast(`${f.no} deleted.`, 'success');
          return reload();
        })
        .catch((err) => {
          // A 409 FLIGHT_HAS_BOOKINGS lands here: the row stays exactly where it
          // was and the toast carries the server's reason ("… set its status to
          // Inactive instead"), rather than a silent success.
          deleteBtn.disabled = false;
          showToast((err && err.message) || 'The flight could not be deleted.', 'error');
        });
    }
  });

  /* ---------- Utilities ---------- */
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* ---------- First render ---------- */
  reload().catch(showLoadFailure);
});
