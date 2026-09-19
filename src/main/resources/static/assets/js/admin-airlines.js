/* =========================================
   YATRA ADMIN — AIRLINES PAGE JS
   CRUD (Master Plan §2.1 #3). Full backend wiring as of the
   fix-plan §12/§13 pass (Session 64) — migrated to the pattern
   admin-flights.js established.

   Reads  : GET /api/airlines          (search + status + paging, all server-side)
            GET /api/airlines/{id}     (the edit modal's fresh copy)
            GET /api/admin/flights     (the "is this airline in use?" check)
   Writes : POST   /api/admin/airlines        (create)
            PUT    /api/admin/airlines/{id}   (edit, and the status toggle)
            DELETE /api/admin/airlines/{id}   (delete)

   All of them through api.js, so this file contains no localStorage
   access and no seed rows of its own. It used to own both
   (`STORAGE_KEY`, `SEED_AIRLINES`, `load()`, `save()`, and an RM-code
   migration), which meant a Save wrote to the browser and never
   reached the database — §12's step 2 could only ever have looked
   like it worked.

   Key rules baked in:
   - Search and the status filter are QUERIES, not array filters: the
     toolbar sends `search`/`status` and the page number, so the row
     count and the pages are MySQL's ("4 airlines", not "4 of the rows
     this tab happens to hold"). The search matches name or IATA code,
     which is what the placeholder has always promised — see
     AirlineRepository.searchAll, where that stopped being a promise
     the API did not keep.
   - The edit modal is filled from a FRESH GET /api/airlines/{id}, not
     from the row on screen (§12 step 4: "fetched fresh, not reused
     from the list's cached data"), so a value another admin changed
     cannot be saved back over itself.
   - IATA uniqueness is the SERVER's answer (409 IATA_EXISTS), because
     the client can no longer see every row — it holds one page. The
     name rule, which has no database constraint behind it, is checked
     with a targeted search query instead of against the page in hand.
   - After any write the list is re-read, so the table can never show a
     row the database does not hold.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 5;

  /* ---------- State ----------
     `airlines` is ONE PAGE of the server's answer — never the whole
     table. `totalElements`/`totalPages` are the server's counts from
     the last list call, so nothing here recomputes a total from the
     rows on screen. */
  let airlines = [];
  let state = { search: '', status: 'ALL', page: 1, totalPages: 1, totalElements: 0 };
  let logoData = null; // a `data:` URL from a fresh upload, or the row's own logo URL
  /* Every read carries a sequence number (see reload()): a slow response for an
     older query must not land on top of a newer one. */
  let readSeq = 0;

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page,
     which also creates the #toast element it writes to. */

  /* ---------- Query, not filtering ----------
     `page` is 1-based in this UI and 0-based on the wire (Spring's
     convention). The status filter sends the page's own vocabulary
     (`Active`/`Inactive`), which is exactly what the column stores. */
  function listQuery() {
    const p = new URLSearchParams();
    if (state.search.trim()) p.set('search', state.search.trim());
    if (state.status !== 'ALL') p.set('status', state.status);
    p.set('page', String(Math.max(0, state.page - 1)));
    p.set('size', String(PAGE_SIZE));
    return '/api/airlines?' + p.toString();
  }

  /* ---------- Table render ---------- */
  function render() {
    const tbody = $('#airlineTableBody');
    tbody.innerHTML = airlines
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

    $('#emptyState').hidden = airlines.length > 0;
    $('#resultCount').textContent =
      `${state.totalElements} airline${state.totalElements === 1 ? '' : 's'}`;
    renderPagination();
  }

  /* Logo chip: image with IATA code-badge fallback (same pattern as searchFlight cards).
     The API answers `/api/airlines/{id}/logo` for a carrier that has one and `""`
     for one that does not, so the fallback is the same empty-string case it always
     was — the browser just fetches the bytes instead of reading a Base64 field. */
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

  function renderPagination() {
    const pages = Math.max(1, state.totalPages);
    const total = state.totalElements;

    $('#pageInfo').textContent = total
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, total)}–${Math.min(state.page * PAGE_SIZE, total)} of ${total} airlines`
      : 'No airlines';

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
     Each one re-queries the backend, and each one resets to page 1:
     page 3 of the old result set has nothing to do with page 3 of the
     new one. Typing is debounced so a search fires one request, not one
     per keystroke. */
  let searchTimer;
  $('#airlineSearch').addEventListener('input', (e) => {
    state.search = e.target.value;
    state.page = 1;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(reloadOrToast, 250);
  });

  $('#statusFilter').addEventListener('change', (e) => {
    state.status = e.target.value;
    state.page = 1;
    reloadOrToast();
  });

  /* ---------- Reads: one path, used on load, on every filter/page change and
     after every write ---------- */
  async function reload() {
    const seq = ++readSeq;
    const resp = await apiGet(listQuery());
    /* Two controls can be in flight together — a filter change while the search box's
       debounced query is still outstanding — and the answers do not have to arrive in
       order. Without this, the older answer redraws the table and the page shows a
       result set nobody asked for. The bookings walk caught exactly that: the
       "payment status = Paid" filter drawn over by the "status = ALL" answer that
       left a moment earlier. */
    if (seq !== readSeq) return; // superseded: a newer read owns the table

    airlines = Array.isArray(resp.airlines) ? resp.airlines : [];

    const total = Number(resp.totalElements);
    state.totalElements = Number.isFinite(total) ? total : airlines.length;
    const pages = Number(resp.totalPages);
    state.totalPages = Number.isFinite(pages) && pages > 0
      ? pages
      : Math.max(1, Math.ceil(state.totalElements / PAGE_SIZE));

    /* A delete on the last page can leave the view past the end of the
       result set (asking for page 4 of 3). The server answers that
       honestly — an empty page with the real counts — so step back
       once rather than rendering "No airlines" over a table that has
       some. */
    if (!airlines.length && state.page > 1 && state.page > state.totalPages) {
      state.page = state.totalPages;
      return reload();
    }

    render();
  }

  /* A failed read that has nothing on screen (first load): say so plainly. */
  function showLoadFailure(err) {
    airlines = [];
    state.totalElements = 0;
    state.totalPages = 1;
    render();
    showToast('Could not load airlines: ' + ((err && err.message) || err), 'error');
  }

  /* A failed read while a table is already displayed (filter, page, refresh):
     keep what is on screen and report the failure. */
  function reloadOrToast() {
    return reload().catch((err) => {
      showToast('Could not load airlines: ' + ((err && err.message) || err), 'error');
    });
  }

  function goToPage(page) {
    const last = Math.max(1, state.totalPages);
    state.page = Math.min(Math.max(1, page), last);
    reloadOrToast();
  }

  /* ---------- Modal open/close ---------- */
  const modal = $('#airlineModal');
  const form = $('#airlineForm');

  function fillForm(airline) {
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

  function openModal() {
    form.reset();
    clearErrors();
    fillForm(null);
  }

  /* Edit opens on the SERVER's copy of that row (§12 step 4), fetched fresh:
     the row on screen may be a page old, and re-saving a stale price or status
     over someone else's change is exactly the silent overwrite this avoids. The
     button is disabled while the read is in flight so a second click cannot
     race it. */
  function openEditModal(id, button) {
    if (button) button.disabled = true;
    apiGet('/api/airlines/' + id)
      .then((airline) => {
        form.reset();
        clearErrors();
        fillForm(airline);
      })
      .catch((err) => {
        showToast('Could not open that airline: ' + ((err && err.message) || err), 'error');
      })
      .finally(() => {
        if (button) button.disabled = false;
      });
  }

  function closeModal() {
    modal.hidden = true;
    logoData = null;
  }

  $('#addAirlineBtn').addEventListener('click', openModal);
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalCancel').addEventListener('click', closeModal);
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !modal.hidden) closeModal();
  });

  /* ---------- Logo upload ----------
     The file is read as a data URL and POSTed in the request body; the API
     stores it in the airline row and serves the bytes back from
     /api/airlines/{id}/logo (Master Plan §3.5), which is why the preview is the
     same <img> either way. Until Save is pressed this preview is local only. */
  const logoInput = $('#logoInput');

  $('#uploadLogoBtn').addEventListener('click', () => logoInput.click());

  logoInput.addEventListener('change', () => {
    const file = logoInput.files && logoInput.files[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      showToast('Logo must be under 2 MB.', 'error');
      logoInput.value = '';
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      logoData = reader.result; // data URL — the shape AirlineRequest.logo accepts
      renderLogoPreview();
      showToast('Logo ready — save the airline to store it.', 'success');
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

  /* Format and required checks are local (they need no network); the two
     DUPLICATE rules are not, because this page holds one page of rows.

     The IATA code is UNIQUE in the schema, so the server refuses a clash with
     409 IATA_EXISTS and the submit handler puts that on the field — the client
     cannot know it, since the clashing row may be on another page.

     The name rule has no database constraint behind it (it is the page's own
     promise: "An airline with this name already exists"), so it is asked of the
     server as a targeted search rather than of the rows in hand. Unpaged on
     purpose: a name check that only saw 5 rows would be worse than no check. */
  async function nameTaken(name, selfId) {
    try {
      const resp = await apiGet('/api/airlines?search=' + encodeURIComponent(name));
      return (resp.airlines || []).some(
        (a) => String(a.name).trim().toLowerCase() === name.toLowerCase()
          && String(a.id) !== String(selfId)
      );
    } catch (err) {
      // Unreachable server: let the write decide rather than blocking a save on
      // a check we could not run.
      return false;
    }
  }

  async function validate() {
    clearErrors();
    let ok = true;

    const name = $('#airlineName');
    const iata = $('#airlineIata');
    const selfId = $('#airlineId').value;

    if (!name.value.trim()) {
      setError(name, 'Airline name is required');
      ok = false;
    } else if (await nameTaken(name.value.trim(), selfId)) {
      setError(name, 'An airline with this name already exists');
      ok = false;
    }

    if (!iata.value.trim()) {
      setError(iata, 'IATA code is required');
      ok = false;
    } else if (!/^[A-Za-z0-9]{2}$/.test(iata.value.trim())) {
      setError(iata, 'IATA code must be exactly 2 letters/digits (e.g. U4)');
      ok = false;
    }

    if (!ok) showToast('Please fix the highlighted fields.', 'error');
    return ok;
  }

  /* ---------- Create / Update ----------
     One body shape for both writes, and the same field set AirlineRequest
     declares. `logo` carries whichever of its three meanings applies: a fresh
     upload (`data:` URL), an empty string when the admin removed it, or the URL
     the API itself supplied on an edit where nothing was chosen — the service
     recognises that last one and keeps the stored image rather than replacing
     it with the string "/api/airlines/3/logo". */
  function payload() {
    return {
      name: $('#airlineName').value.trim(),
      iata: $('#airlineIata').value.trim().toUpperCase(),
      description: $('#airlineDesc').value.trim(),
      status: $('#airlineStatus').value,
      logo: logoData || ''
    };
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!(await validate())) return;

    const id = $('#airlineId').value;
    const body = payload();
    const saveBtn = $('#modalSave');
    if (saveBtn) saveBtn.disabled = true;

    try {
      if (id) {
        await apiPut('/api/admin/airlines/' + id, body);
      } else {
        await apiPost('/api/admin/airlines', body);
      }
    } catch (err) {
      // The modal stays open and the message is the server's own sentence.
      // A 409 names the code that clashed, so it also lands on the field — the
      // same inline surface the local checks use.
      if (err && err.code === 'IATA_EXISTS') setError($('#airlineIata'), err.message);
      if (saveBtn) saveBtn.disabled = false;
      showToast((err && err.message) || 'The airline could not be saved.', 'error');
      return;
    }

    if (saveBtn) saveBtn.disabled = false;
    closeModal();
    showToast(`${body.name} ${id ? 'updated' : 'added'}.`, 'success');
    await reloadOrToast();
  });

  $('#airlineTableBody').addEventListener('click', (e) => {
    const editBtn = e.target.closest('[data-edit]');
    const toggleBtn = e.target.closest('[data-toggle]');
    const deleteBtn = e.target.closest('[data-delete]');

    if (editBtn) {
      openEditModal(editBtn.dataset.edit, editBtn);
    }

    /* Activate/Disable is the edit write with one field changed — same endpoint,
       same body, so there is no second code path that could drift from it. The
       row on screen supplies every other field; the server answers the new state. */
    if (toggleBtn) {
      const a = airlines.find((x) => String(x.id) === toggleBtn.dataset.toggle);
      if (!a) return;
      const next = a.status === 'Active' ? 'Inactive' : 'Active';
      toggleBtn.disabled = true;
      apiPut('/api/admin/airlines/' + a.id, {
        name: a.name,
        iata: a.iata,
        description: a.description || '',
        status: next,
        logo: a.logo || '' // the API's own URL echo: the stored image is kept
      })
        .then(() => {
          showToast(`${a.name} is now ${next.toLowerCase()}.`, 'success');
          return reload();
        })
        .catch((err) => {
          toggleBtn.disabled = false;
          showToast((err && err.message) || 'The airline could not be updated.', 'error');
        });
    }

    if (deleteBtn) {
      const a = airlines.find((x) => String(x.id) === deleteBtn.dataset.delete);
      if (a) deleteAirline(a, deleteBtn);
    }
  });

  /* Flights reference a carrier by id, so deleting an airline that still operates
     flights would dangle those references. In real mode the API refuses it with 409
     AIRLINE_HAS_FLIGHTS — the same guard `Service/AirlineService` applies — so without
     this pre-check the admin would get an error toast for an action the page had
     suggested was fine. This is the check admin-destinations.js already makes for
     routes, worded the same way on purpose so both pages give one piece of advice:
     disable it instead. */
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

  function deleteAirline(a, button) {
    flightsUsing(a.id).then((inUse) => {
      if (inUse.length) {
        showToast(
          `${a.name} (${a.iata}) is used by ${inUse.length} flight${inUse.length === 1 ? '' : 's'} — disable it instead.`,
          'error'
        );
        return;
      }

      if (!confirm(`Delete "${a.name}"? This cannot be undone.`)) return;

      button.disabled = true;
      apiDelete('/api/admin/airlines/' + a.id)
        .then(() => {
          showToast(`${a.name} deleted.`, 'success');
          return reload();
        })
        .catch((err) => {
          // The API's own refusal (a flight this page's read could not see, or any
          // other integrity rule) lands here with the server's sentence, and the row
          // stays exactly where it was rather than silently disappearing from the table.
          button.disabled = false;
          showToast((err && err.message) || 'The airline could not be deleted.', 'error');
        });
    });
  }

  /* ---------- Utilities ---------- */
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  /* ---------- First render ----------
     No seed fallback: if the API cannot be reached the page says so, rather than
     drawing airlines that are not in the database. */
  reload().catch(showLoadFailure);
});
