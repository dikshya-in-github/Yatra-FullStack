/* =========================================
   YATRA ADMIN — SHARED ADMIN JS
   Loaded by every admin-*.html shell page.
   Mock session for now — the guard mirrors the
   future backend enforcement (Master Plan §2.3):
   when JWT lands, replace the sessionStorage
   check with a real token/role validation.
   ========================================= */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (s, c = document) => c.querySelector(s);

  // Key name lives in config.js (one place for every storage key; api.js reads
  // the same one for GET/POST /api/admin/profile). The literal is the fallback
  // for a page that forgot to load config.js — the guard must not silently
  // pass just because a script tag is missing.
  const ADMIN_SESSION_KEY =
    (typeof YATRA_CONFIG !== 'undefined' && YATRA_CONFIG.ADMIN_SESSION_KEY) || 'yatra_admin_session';

  /* ---------- 1. Session guard (mock JWT) ---------- */
  let admin = null;
  try {
    admin = JSON.parse(sessionStorage.getItem(ADMIN_SESSION_KEY) || 'null');
  } catch (err) {
    admin = null;
  }

  if (!admin || admin.role !== 'ADMIN') {
    // No valid admin session → back to the admin login page.
    window.location.href = './admin-login.html';
    return;
  }

  /* ---------- 2. Fill user identity ---------- */
  const initial = (admin.name || 'A').trim().charAt(0).toUpperCase();
  const slots = [
    ['#adminName', admin.name],
    ['#adminAvatar', initial],
    ['#sidebarName', admin.name],
    ['#sidebarEmail', admin.email],
    ['#sidebarAvatar', initial]
  ];
  slots.forEach(([sel, val]) => {
    const el = $(sel);
    if (el && val) el.textContent = val;
  });

  /* ---------- 3. Active sidebar link + topbar title ---------- */
  const page = document.body.dataset.adminPage || '';
  const activeLink = $(`.admin-nav-item[data-page="${page}"]`);
  if (activeLink) {
    activeLink.classList.add('active');
    activeLink.setAttribute('aria-current', 'page');

    const topbarTitle = $('#topbarTitle');
    if (topbarTitle) {
      // Strip any "soon" chip text from the label.
      topbarTitle.textContent = activeLink.childNodes[0]
        ? activeLink.childNodes[0].textContent.trim()
        : 'Admin';
    }
  }

  /* ---------- 4. Mobile sidebar toggle ---------- */
  const toggleBtn = $('#sidebarToggle');
  const backdrop = $('#sidebarBackdrop');

  const setSidebar = (open) => {
    document.body.classList.toggle('sidebar-open', open);
    if (toggleBtn) toggleBtn.setAttribute('aria-expanded', String(open));
    if (backdrop) backdrop.hidden = false; // CSS handles visibility via opacity
  };

  if (toggleBtn) {
    toggleBtn.addEventListener('click', () =>
      setSidebar(!document.body.classList.contains('sidebar-open'))
    );
  }
  if (backdrop) {
    backdrop.addEventListener('click', () => setSidebar(false));
  }
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') setSidebar(false);
  });

  /* ---------- 5. Logout ---------- */
  const logoutBtn = $('#adminLogout');
  if (logoutBtn) {
    logoutBtn.addEventListener('click', () => {
      sessionStorage.removeItem(ADMIN_SESSION_KEY);
      window.location.href = './admin-login.html';
    });
  }
});
