document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. CATEGORY FILTERING + LOAD MORE
    // ==========================================
    const grid = document.getElementById('postsGrid');
    const filterBtns = document.querySelectorAll('.filter-btn');
    const loadMoreBtn = document.getElementById('loadMoreBtn');
    const emptyState = document.getElementById('emptyState');

    if (grid) {
        const allCards = Array.prototype.slice.call(grid.querySelectorAll('.post-card'));
        const BATCH = 6;          // cards shown initially / per load
        let activeFilter = 'all';
        let visibleCount = BATCH;

        const render = () => {
            const matching = allCards.filter(card =>
                activeFilter === 'all' || card.dataset.category === activeFilter
            );

            // Hide every card, then reveal the ones we want
            allCards.forEach(card => { card.style.display = 'none'; });
            matching.slice(0, visibleCount).forEach(card => {
                card.style.display = 'flex';
                card.classList.add('active'); // ensure reveal state shows on filter change
            });

            // Empty state + load-more visibility
            if (emptyState) emptyState.classList.toggle('show', matching.length === 0);
            if (loadMoreBtn) loadMoreBtn.classList.toggle('hidden', visibleCount >= matching.length);
        };

        filterBtns.forEach(btn => {
            btn.addEventListener('click', () => {
                filterBtns.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                activeFilter = btn.dataset.filter;
                visibleCount = BATCH;
                render();
            });
        });

        loadMoreBtn?.addEventListener('click', () => {
            visibleCount += BATCH;
            render();
        });

        render();
    }

    // ==========================================
    // 2. NEWSLETTER FORM
    // ==========================================
    const form = document.getElementById('newsletterForm');
    const emailInput = document.getElementById('newsletterEmail');
    const msg = document.getElementById('newsletterMsg');
    const emailRe = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

    if (form) {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const value = emailInput.value.trim();
            if (!emailRe.test(value)) {
                msg.textContent = 'Please enter a valid email address.';
                msg.className = 'newsletter-msg err';
                return;
            }
            // Simulated subscription (replace with fetch() to real API later)
            msg.textContent = "You're subscribed! Watch your inbox for our next story.";
            msg.className = 'newsletter-msg ok';
            emailInput.value = '';
        });
    }
});
