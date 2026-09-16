document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. SEARCH + CATEGORY FILTER STATE
    // ==========================================
    const articles = document.querySelectorAll('.article-item');
    const searchInput = document.getElementById('helpSearch');
    const clearSearchBtn = document.getElementById('clearSearch');
    const resultCount = document.getElementById('resultCount');
    const emptyArticles = document.getElementById('emptyArticles');
    const filterChips = document.querySelectorAll('.filter-chip');
    const categoryCards = document.querySelectorAll('.category-card');
    const articlesSection = document.getElementById('articles');

    let activeCategory = 'all';
    let searchTerm = '';
    const totalArticles = articles.length;

    function applyFilters() {
        let visible = 0;
        articles.forEach(article => {
            const matchCat = activeCategory === 'all' || article.dataset.category === activeCategory;
            const text = article.textContent.toLowerCase();
            const matchSearch = !searchTerm || text.includes(searchTerm);
            const show = matchCat && matchSearch;
            article.style.display = show ? '' : 'none';
            if (show) visible++;
            // Close expanded article if it gets hidden
            if (!show && article.classList.contains('open')) {
                article.classList.remove('open');
                article.querySelector('.article-answer').style.maxHeight = null;
            }
        });
        if (resultCount) {
            resultCount.innerHTML = `Showing <strong>${visible}</strong> of ${totalArticles} articles`;
        }
        if (emptyArticles) {
            emptyArticles.style.display = visible === 0 ? 'block' : 'none';
        }
    }

    function setCategory(cat) {
        activeCategory = cat;
        filterChips.forEach(chip => chip.classList.toggle('active', chip.dataset.filter === cat));
        categoryCards.forEach(card => card.classList.toggle('active', card.dataset.category === cat));
        applyFilters();
    }

    function scrollToArticles() {
        if (articlesSection) articlesSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    // Hero live search
    if (searchInput) {
        searchInput.addEventListener('input', () => {
            searchTerm = searchInput.value.trim().toLowerCase();
            if (clearSearchBtn) clearSearchBtn.classList.toggle('show', searchTerm.length > 0);
            applyFilters();
        });
    }
    if (clearSearchBtn) {
        clearSearchBtn.addEventListener('click', () => {
            searchInput.value = '';
            searchTerm = '';
            clearSearchBtn.classList.remove('show');
            applyFilters();
            searchInput.focus();
        });
    }

    // Popular search chips
    document.querySelectorAll('.pop-chip').forEach(chip => {
        chip.addEventListener('click', () => {
            if (!searchInput) return;
            searchInput.value = chip.dataset.term;
            searchTerm = chip.dataset.term.toLowerCase();
            if (clearSearchBtn) clearSearchBtn.classList.add('show');
            setCategory('all');
            scrollToArticles();
        });
    });

    // Filter chips
    filterChips.forEach(chip => {
        chip.addEventListener('click', () => setCategory(chip.dataset.filter));
    });

    // Category cards → filter + scroll
    categoryCards.forEach(card => {
        card.addEventListener('click', () => {
            setCategory(card.dataset.category);
            scrollToArticles();
        });
    });

    // Clear all filters (empty state)
    document.getElementById('clearFiltersBtn')?.addEventListener('click', () => {
        if (searchInput) searchInput.value = '';
        searchTerm = '';
        if (clearSearchBtn) clearSearchBtn.classList.remove('show');
        setCategory('all');
    });

    // ==========================================
    // 2. ARTICLE ACCORDION (single-open)
    // ==========================================
    document.querySelectorAll('.article-question').forEach(btn => {
        btn.addEventListener('click', () => {
            const item = btn.closest('.article-item');
            const answer = item.querySelector('.article-answer');
            const isOpen = item.classList.contains('open');

            document.querySelectorAll('.article-item.open').forEach(openItem => {
                openItem.classList.remove('open');
                openItem.querySelector('.article-answer').style.maxHeight = null;
            });

            if (!isOpen) {
                item.classList.add('open');
                answer.style.maxHeight = answer.scrollHeight + 'px';
            }
        });
    });

    // ==========================================
    // 3. "WAS THIS HELPFUL?" VOTING (mock — no backend yet)
    // ==========================================
    document.querySelectorAll('.vote-row').forEach(row => {
        const buttons = row.querySelectorAll('.vote-btn');
        const thanks = row.querySelector('.vote-thanks');
        let voted = false;

        buttons.forEach(btn => {
            btn.addEventListener('click', () => {
                if (voted) return;
                voted = true;
                const countEl = btn.querySelector('.vote-count');
                countEl.textContent = parseInt(countEl.textContent) + 1;
                btn.classList.add('voted');
                buttons.forEach(b => { b.disabled = true; });
                if (thanks) thanks.classList.add('show');
                console.log(`Article vote: ${btn.dataset.vote}`);
            });
        });
    });
});
