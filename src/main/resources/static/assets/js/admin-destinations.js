/* =========================================
   YATRA ADMIN — DESTINATIONS PAGE JS
   Destination CRUD (Master Plan §2.1 #7). Fully wired to the backend
   as of the fix-plan §12/§13 pass — migrated to the pattern
   admin-flights.js established and admin-airlines.js proved.

   Reads  : GET  /api/admin/destinations       (search + status + paging, server-side)
            GET  /api/destinations/{id}        (the edit modal's FRESH copy)
   Upload : POST /api/admin/destinations/image (multipart → Cloudinary → {imageUrl, publicId})
   Writes : POST   /api/admin/destinations        (create)
            PUT    /api/admin/destinations/{id}    (edit, and the status toggle)
            DELETE /api/admin/destinations/{id}    (delete)

   Every one of them through api.js, so this file contains no localStorage
   access and no seed rows of its own. It used to own both (`STORAGE_KEY`,
   `SEED_DESTINATIONS`, `load()`, `save()`), which meant a Save wrote to the
   browser and never reached the database — §12's step 2 could only ever have
   looked like it worked.

   Image handling, per Master Plan §3.5's hybrid split: a destination stores a
   URL, never bytes. The file goes to Cloudinary through the API's own endpoint
   and the row keeps the returned pair (`imageUrl` what the browser renders,
   `imagePublicId` the only handle its cleanup accepts). The demo's data-URL
   conversion is gone with the mock — there is nothing to store offline now that
   the URL is the whole contract.

   Key rules baked in:
   - Search and the status filter are QUERIES, not array filters: the toolbar
     sends `search`/`status` and the page number, so the count and the pages are
     MySQL's ("11 destinations", not "8 of the rows this tab happens to hold").
   - The edit modal is filled from a FRESH GET /api/destinations/{id} (§12 step 4:
     "fetched fresh, not reused from the list's cached data"), so a value another
     admin changed cannot be saved back over itself.
   - Uniqueness is the SERVER's answer — 409 DESTINATION_CODE_EXISTS or
     DESTINATION_CITY_EXISTS, landed inline on the field it names. The page used
     to check both against the rows it was holding, which stopped meaning
     anything the moment it held one page.
   - The airport code is validated for SHAPE here (three letters) and for
     uniqueness by the API. It is deliberately no longer restricted to the 11
     codes searchFlight.js's map knows: that list belongs to the storefront, the
     API does not enforce it (see DestinationRequest), and with all 11 already in
     the database the restriction made "Add Destination" impossible to complete.
   - Deleting an airport a flight's route uses is the API's refusal (409
     DESTINATION_HAS_FLIGHTS, "… disable it instead"). The page no longer reads
     every flight to guess at it: the confirm is asked first, and the server's own
     sentence is what the toast shows when it declines.
   - After any write the list is re-read, so the table can never show a row the
     database does not hold.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  const PAGE_SIZE = 8;

  /* ---------- State ----------
     `destinations` is ONE PAGE of the server's answer — never the whole table.
     `totalElements`/`totalPages` are the server's counts from the last list
     call, so nothing here recomputes a total from the rows on screen.
     `uploaded` is the last upload's { imageUrl, publicId } pair, or null. */
  let destinations = [];
  let state = { search: '', status: 'ALL', page: 1, totalPages: 1, totalElements: 0 };
  let uploaded = null;
  /* Every read carries a sequence number (see reload()): a slow response for an
     older query must not land on top of a newer one. */
  let readSeq = 0;

  /* ---------- Toast ---------- */
  /* showToast() lives in toast.js (§9) — one implementation for every page,
     which also creates the #toast element it writes to. */

  /* ---------- Query, not filtering ----------
     `page` is 1-based in this UI and 0-based on the wire (Spring's convention).
     The status filter sends the page's own vocabulary (`Active`/`Inactive`),
     which is exactly what the column stores. */
  function listQuery() {
    const p = new URLSearchParams();
    if (state.search.trim()) p.set('search', state.search.trim());
    if (state.status !== 'ALL') p.set('status', state.status);
    p.set('page', String(Math.max(0, state.page - 1)));
    p.set('size', String(PAGE_SIZE));
    return '/api/admin/destinations?' + p.toString();
  }

  /* ---------- Table render ---------- */
  function render() {
    const tbody = $('#destTableBody');
    tbody.innerHTML = destinations
      .map(
        (d) => `
      <tr>
        <td>${imgChip(d)}</td>
        <td><strong>${escapeHtml(d.city)}</strong></td>
        <td>${escapeHtml(d.airport || '—')}</td>
        <td><span class="badge badge-neutral">${escapeHtml(d.code)}</span></td>
        <td class="desc-cell">${escapeHtml(d.description || '—')}</td>
        <td><span class="badge ${d.status === 'Active' ? 'badge-success' : 'badge-neutral'}">${escapeHtml(d.status)}</span></td>
        <td>
          <div class="row-actions">
            <button type="button" class="icon-btn" data-edit="${d.id}" aria-label="Edit ${escapeHtml(d.city)}"><i class="fa-solid fa-pen"></i></button>
            <button type="button" class="icon-btn" data-toggle="${d.id}" aria-label="${d.status === 'Active' ? 'Disable' : 'Activate'} ${escapeHtml(d.city)}"><i class="fa-solid fa-${d.status === 'Active' ? 'power-off' : 'rotate-left'}"></i></button>
            <button type="button" class="icon-btn icon-btn-danger" data-delete="${d.id}" aria-label="Delete ${escapeHtml(d.city)}"><i class="fa-solid fa-trash"></i></button>
          </div>
        </td>
      </tr>`
      )
      .join('');

    $('#emptyState').hidden = destinations.length > 0;
    $('#resultCount').textContent =
      `${state.totalElements} destination${state.totalElements === 1 ? '' : 's'}`;
    renderPagination();
  }

  /* Image chip: the stored Cloudinary URL, with the code-badge fallback the
     mock already used for a destination without an image (§3.5's contrast with
     the airline logo — this is a URL the browser fetches from the CDN, not an
     endpoint on this server). */
  function imgChip(d) {
    if (d.imageUrl) {
      return `<span class="logo-chip"><img src="${escapeHtml(d.imageUrl)}" alt="${escapeHtml(d.city)}"
        onerror="this.style.display='none'; this.nextElementSibling.style.display='flex';">
        <span class="logo-fallback" style="display:none;">${escapeHtml(d.code)}</span></span>`;
    }
    return `<span class="logo-chip"><span class="logo-fallback">${escapeHtml(d.code)}</span></span>`;
  }

  function renderPagination() {
    const pages = Math.max(1, state.totalPages);
    const total = state.totalElements;

    $('#pageInfo').textContent = total
      ? `Showing ${Math.min((state.page - 1) * PAGE_SIZE + 1, total)}–${Math.min(state.page * PAGE_SIZE, total)} of ${total} destinations`
      : 'No destinations';

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
     Each one re-queries the backend, and each one resets to page 1: page 3 of
     the old result set has nothing to do with page 3 of the new one. Typing is
     debounced so a search fires one request, not one per keystroke. */
  let searchTimer;
  $('#destSearch').addEventListener('input', (e) => {
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
       result set nobody asked for (the bookings walk caught it there). */
    if (seq !== readSeq) return; // superseded: a newer read owns the table

    destinations = Array.isArray(resp.destinations) ? resp.destinations : [];

    const total = Number(resp.totalElements);
    state.totalElements = Number.isFinite(total) ? total : destinations.length;
    const pages = Number(resp.totalPages);
    state.totalPages = Number.isFinite(pages) && pages > 0
      ? pages
      : Math.max(1, Math.ceil(state.totalElements / PAGE_SIZE));

    /* A delete on the last page can leave the view past the end of the result
       set (asking for page 2 of 1). The server answers that honestly — an empty
       page with the real counts — so step back once rather than rendering "No
       destinations" over a table that has some. */
    if (!destinations.length && state.page > 1 && state.page > state.totalPages) {
      state.page = state.totalPages;
      return reload();
    }

    render();
  }

  /* A failed read that has nothing on screen (first load): say so plainly. */
  function showLoadFailure(err) {
    destinations = [];
    state.totalElements = 0;
    state.totalPages = 1;
    render();
    showToast('Could not load destinations: ' + ((err && err.message) || err), 'error');
  }

  /* A failed read while a table is already displayed (filter, page, refresh):
     keep what is on screen and report the failure. */
  function reloadOrToast() {
    return reload().catch((err) => {
      showToast('Could not load destinations: ' + ((err && err.message) || err), 'error');
    });
  }

  function goToPage(page) {
    const last = Math.max(1, state.totalPages);
    state.page = Math.min(Math.max(1, page), last);
    reloadOrToast();
  }

  /* ---------- Modal open/close ---------- */
  const modal = $('#destModal');
  const form = $('#destForm');

  function fillForm(destination) {
    uploaded = null;
    $('#destId').value = destination ? destination.id : '';
    $('#destCity').value = destination ? destination.city : '';
    $('#destCode').value = destination ? destination.code : '';
    $('#destAirport').value = destination ? destination.airport || '' : '';
    $('#destDesc').value = destination ? destination.description || '' : '';
    $('#destImageUrl').value = destination ? destination.imageUrl || '' : '';
    $('#destStatus').value = destination ? destination.status : 'Active';
    $('#modalTitle').textContent = destination ? 'Edit Destination' : 'Add Destination';
    renderImgPreview();
    modal.hidden = false;
    $('#destCity').focus({ preventScroll: true });
  }

  function openModal() {
    form.reset();
    clearErrors();
    $('#imgInput').value = '';
    fillForm(null);
  }

  /* Edit opens on the SERVER's copy of that row (§12 step 4), fetched fresh —
     the row on screen may be a page old. The button is disabled while the read
     is in flight so a second click cannot race it. */
  function openEditModal(id, button) {
    if (button) button.disabled = true;
    apiGet('/api/destinations/' + id)
      .then((destination) => {
        form.reset();
        clearErrors();
        $('#imgInput').value = '';
        fillForm(destination);
      })
      .catch((err) => {
        showToast('Could not open that destination: ' + ((err && err.message) || err), 'error');
      })
      .finally(() => {
        if (button) button.disabled = false;
      });
  }

  function closeModal() {
    modal.hidden = true;
    uploaded = null;
  }

  $('#addDestBtn').addEventListener('click', openModal);
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalCancel').addEventListener('click', closeModal);
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
  });

  /* ---------- Image upload ----------
     The file goes to `POST /api/admin/destinations/image`, which sends it to
     Cloudinary and answers with the two values the row keeps. The preview is
     Cloudinary's own URL, so what the admin sees before saving is what the
     storefront will render. Until Save is pressed the row is untouched — a
     failed upload costs a retry, not the rest of the form. */
  const imgInput = $('#imgInput');

  $('#uploadImgBtn').addEventListener('click', () => imgInput.click());

  imgInput.addEventListener('change', () => {
    const file = imgInput.files && imgInput.files[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      showToast('Image must be under 2 MB.', 'error');
      imgInput.value = '';
      return;
    }

    const uploadBtn = $('#uploadImgBtn');
    uploadBtn.disabled = true;
    apiUpload('/api/admin/destinations/image', file)
      .then((answer) => {
        uploaded = { imageUrl: answer.imageUrl, publicId: answer.publicId };
        $('#destImageUrl').value = answer.imageUrl || '';
        renderImgPreview();
        showToast('Image uploaded — save the destination to store it.', 'success');
      })
      .catch((err) => {
        // Nothing was written anywhere: the previous image (if any) is still the
        // row's, and the message is the server's own.
        showToast((err && err.message) || 'The image could not be uploaded.', 'error');
      })
      .finally(() => {
        uploadBtn.disabled = false;
      });
  });

  $('#removeImgBtn').addEventListener('click', () => {
    uploaded = null;
    imgInput.value = '';
    $('#destImageUrl').value = '';
    renderImgPreview();
  });

  /* The preview follows the URL field as well as the upload, because a pasted
     Cloudinary link is a legitimate way to set the image — and it is what the
     row will actually store. */
  $('#destImageUrl').addEventListener('input', () => {
    if (uploaded && $('#destImageUrl').value.trim() !== uploaded.imageUrl) {
      // The field no longer shows the upload's URL, so its public id must not
      // travel with it: the pair rule in DestinationService is what keeps a
      // replaced asset from being orphaned or wrongly deleted.
      uploaded = null;
    }
    renderImgPreview();
  });

  function renderImgPreview() {
    const preview = $('#imgPreview');
    const removeBtn = $('#removeImgBtn');
    const url = currentImageUrl();
    if (url) {
      preview.innerHTML = `<img src="${escapeHtml(url)}" alt="Image preview">`;
      removeBtn.hidden = false;
    } else {
      preview.innerHTML = '<span>No file</span>';
      removeBtn.hidden = true;
    }
  }

  function currentImageUrl() {
    return uploaded ? uploaded.imageUrl : $('#destImageUrl').value.trim();
  }

  /* ---------- Styled confirm modal ----------
     Delete asks first (§12 step 5). The API is the authority on whether the
     deletion is allowed at all, so the refusal is reported after the answer —
     and its message is the server's. */
  const confirmModal = $('#confirmModal');
  let confirmAction = null;

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

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!confirmModal.hidden) { hideConfirm(); return; }
    if (!modal.hidden) closeModal();
  });

  /* ---------- Validation ----------
     Shape and required checks are local (they need no network). Both DUPLICATE
     rules are the server's, because this page holds one page of rows: the code
     is unique in the schema and the city is unique by the service's own check,
     so the submit handler lands 409 DESTINATION_CODE_EXISTS /
     DESTINATION_CITY_EXISTS on the field they name. */
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
    }

    const codeVal = code.value.trim().toUpperCase();
    if (!codeVal) {
      setError(code, 'Airport code is required');
      ok = false;
    } else if (!/^[A-Za-z]{3}$/.test(codeVal)) {
      setError(code, 'Airport code must be exactly 3 letters (e.g. PKR)');
      ok = false;
    }

    if (!airport.value.trim()) {
      setError(airport, 'Airport name is required');
      ok = false;
    }

    if (!ok) showToast('Please fix the highlighted fields.', 'error');
    return ok;
  }

  /* ---------- Create / Update ----------
     One body shape for both writes, and the same field set DestinationRequest
     declares. `imagePublicId` is sent only when it belongs to the URL being
     sent (a fresh upload); otherwise it is empty, which the service reads as
     "the URL alone is all I have" — and an unchanged URL keeps the stored id.
     Clearing the image sends "" and the pair rule drops both. */
  function payload() {
    return {
      city: $('#destCity').value.trim(),
      code: $('#destCode').value.trim().toUpperCase(),
      airport: $('#destAirport').value.trim(),
      description: $('#destDesc').value.trim(),
      imageUrl: currentImageUrl(),
      imagePublicId: uploaded ? uploaded.publicId : '',
      status: $('#destStatus').value
    };
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!validate()) return;

    const id = $('#destId').value;
    const body = payload();
    const saveBtn = $('#destSaveBtn');
    if (saveBtn) saveBtn.disabled = true;

    try {
      if (id) {
        await apiPut('/api/admin/destinations/' + id, body);
      } else {
        await apiPost('/api/admin/destinations', body);
      }
    } catch (err) {
      // The modal stays open and the message is the server's own sentence. Both
      // refusals name the value that clashed, so they land on their field too.
      if (err && err.code === 'DESTINATION_CODE_EXISTS') setError($('#destCode'), err.message);
      if (err && err.code === 'DESTINATION_CITY_EXISTS') setError($('#destCity'), err.message);
      if (saveBtn) saveBtn.disabled = false;
      showToast((err && err.message) || 'The destination could not be saved.', 'error');
      return;
    }

    if (saveBtn) saveBtn.disabled = false;
    closeModal();
    showToast(`${body.city} ${id ? 'updated' : 'added'}.`, 'success');
    await reloadOrToast();
  });

  /* ---------- Row actions ---------- */
  $('#destTableBody').addEventListener('click', (e) => {
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
      const d = destinations.find((x) => String(x.id) === toggleBtn.dataset.toggle);
      if (!d) return;
      const next = d.status === 'Active' ? 'Inactive' : 'Active';
      toggleBtn.disabled = true;
      apiPut('/api/admin/destinations/' + d.id, {
        city: d.city,
        code: d.code,
        airport: d.airport || '',
        description: d.description || '',
        imageUrl: d.imageUrl || '',
        status: next
      })
        .then(() => {
          showToast(`${d.city} is now ${next.toLowerCase()}.`, 'success');
          return reload();
        })
        .catch((err) => {
          toggleBtn.disabled = false;
          showToast((err && err.message) || 'The destination could not be updated.', 'error');
        });
    }

    if (deleteBtn) {
      const d = destinations.find((x) => String(x.id) === deleteBtn.dataset.delete);
      if (d) deleteDestination(d, deleteBtn);
    }
  });

  /* Flights reference an airport by id, so deleting one a route still uses would
     dangle those references. The API refuses it — 409 DESTINATION_HAS_FLIGHTS,
     "… is used by N flight(s) — disable it instead." — and the count in that
     sentence is a query, where the mock's version read every flight into the
     browser to answer the same question. So the confirm is asked first and the
     server's refusal is what the toast shows. */
  function deleteDestination(d, button) {
    askConfirm({
      title: 'Delete this destination?',
      message: `${d.city} (${d.code}) — ${d.airport || 'this airport'} will be removed permanently.`,
      yesLabel: 'Delete destination',
      icon: 'trash',
      onYes: () => {
        button.disabled = true;
        apiDelete('/api/admin/destinations/' + d.id)
          .then(() => {
            showToast(`${d.city} deleted.`, 'success');
            return reload();
          })
          .catch((err) => {
            // A route still uses it, or any other integrity rule: the row stays
            // exactly where it was rather than silently leaving the table.
            button.disabled = false;
            showToast((err && err.message) || 'The destination could not be deleted.', 'error');
          });
      }
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
     drawing airports that are not in the database. */
  reload().catch(showLoadFailure);
});
