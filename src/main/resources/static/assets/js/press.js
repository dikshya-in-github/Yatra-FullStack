document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. NEWSROOM CATEGORY FILTERS
    // ==========================================
    const filterChips = document.querySelectorAll('.filter-chip');
    const newsCards = document.querySelectorAll('.news-card');
    const emptyNews = document.getElementById('emptyNews');

    filterChips.forEach(chip => {
        chip.addEventListener('click', () => {
            filterChips.forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            const filter = chip.dataset.filter;
            let visibleCount = 0;
            newsCards.forEach(card => {
                const show = filter === 'all' || card.dataset.category === filter;
                card.style.display = show ? '' : 'none';
                if (show) visibleCount++;
            });
            if (emptyNews) emptyNews.style.display = visibleCount === 0 ? 'block' : 'none';
        });
    });

    // ==========================================
    // 2. MEDIA KIT DOWNLOAD BUTTONS (simulated)
    // ==========================================
    document.querySelectorAll('.kit-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            if (btn.disabled) return;
            const originalHTML = btn.innerHTML;
            btn.disabled = true;
            btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Preparing...';
            console.log('Media kit download requested:', btn.dataset.file);
            // Simulated API delay (replace with real file download later)
            setTimeout(() => {
                btn.innerHTML = '<i class="fa-solid fa-check"></i> Downloaded';
                setTimeout(() => {
                    btn.innerHTML = originalHTML;
                    btn.disabled = false;
                }, 2000);
            }, 1200);
        });
    });

    // ==========================================
    // 3. NEWSLETTER SUBSCRIPTION
    // ==========================================
    const newsletterForm = document.getElementById('newsletterForm');
    const newsletterEmail = document.getElementById('newsletterEmail');
    const newsletterBtn = document.getElementById('newsletterBtn');
    const newsletterSuccess = document.getElementById('newsletterSuccess');

    if (newsletterForm) {
        newsletterForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const email = newsletterEmail.value.trim();
            if (!email) return;
            console.log('Newsletter subscription:', email);

            newsletterBtn.disabled = true;
            newsletterBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i>';

            // Simulated API delay (replace with fetch() to real API later)
            setTimeout(() => {
                newsletterSuccess.classList.add('show');
                newsletterBtn.innerHTML = '<i class="fa-solid fa-check"></i> Done';
                newsletterEmail.value = '';
                setTimeout(() => {
                    newsletterBtn.disabled = false;
                    newsletterBtn.textContent = 'Subscribe';
                }, 2500);
            }, 1000);
        });
    }
});
