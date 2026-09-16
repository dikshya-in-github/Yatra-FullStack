/* =====================================================
   DESTINATIONS PAGE JS — page-specific logic only.
   Navbar scroll, mobile menu, scroll reveal, count-up
   stats and back-to-top are shared: assets/js/homeLogged.js
   (loaded BEFORE this file).
   ===================================================== */
document.addEventListener("DOMContentLoaded", function () {
  /* ---------- 1. Load More (reveal next batch with stagger) ---------- */
  var BATCH = 6;
  var grid = document.getElementById("destGrid");
  var loadBtn = document.getElementById("loadMoreBtn");
  var feedback = document.getElementById("loadFeedback");
  if (grid && loadBtn) {
    var hiddenCards = Array.prototype.slice.call(
      grid.querySelectorAll(".dest-extra[hidden]")
    );

    loadBtn.addEventListener("click", function () {
      loadBtn.classList.add("loading");
      var batch = hiddenCards.splice(0, BATCH);

      // Brief delay so the spinner state is visible before cards pop in
      window.setTimeout(function () {
        batch.forEach(function (card, i) {
          card.hidden = false;
          card.classList.add("active");
          card.style.animationDelay = i * 90 + "ms";
        });
        var remaining = hiddenCards.length;
        feedback.textContent = batch.length
          ? batch.length + " more destinations loaded."
          : "";
        loadBtn.classList.remove("loading");

        if (remaining === 0) {
          loadBtn.classList.add("hidden");
          feedback.textContent =
            "You've seen all our destinations — head to Book a Flight!";
        }
      }, 400);
    });

    if (hiddenCards.length === 0) loadBtn.classList.add("hidden");
  }

  /* ---------- 2. Image fallback (themed gradient if photo missing) ---------- */
  document.querySelectorAll(".dest-card img").forEach(function (img) {
    img.addEventListener("error", function () {
      var card = img.closest(".dest-card");
      if (!card || card.querySelector(".dest-fallback")) return;

      img.style.display = "none";

      var grad = document.createElement("span");
      grad.className = "dest-fallback";
      var palette = [
        "#0ea371,#0f766e",
        "#0ea5e9,#1e3a8a",
        "#f59e0b,#be123c",
        "#14b8a6,#0ea371",
        "#6366f1,#0f172a"
      ];
      var picked = palette[Math.floor(Math.random() * palette.length)];
      grad.style.background = "linear-gradient(135deg, " + picked + ")";
      card.insertBefore(grad, card.firstChild);
    });
  });
});
