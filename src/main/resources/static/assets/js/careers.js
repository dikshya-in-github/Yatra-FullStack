document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. JOB FILTERS
    // ==========================================
    const filterChips = document.querySelectorAll('.filter-chip');
    const jobCards = document.querySelectorAll('.job-card');
    const emptyJobs = document.getElementById('emptyJobs');

    filterChips.forEach(chip => {
        chip.addEventListener('click', () => {
            filterChips.forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            const filter = chip.dataset.filter;
            let visibleCount = 0;
            jobCards.forEach(card => {
                const show = filter === 'all' || card.dataset.category === filter;
                card.style.display = show ? '' : 'none';
                if (show) visibleCount++;
            });
            if (emptyJobs) emptyJobs.style.display = visibleCount === 0 ? 'block' : 'none';
        });
    });

    // ==========================================
    // 2. APPLICATION MODAL
    // ==========================================
    const applyModal = document.getElementById('applyModal');
    const modalRole = document.getElementById('modalRole');
    const closeModalBtn = document.getElementById('closeModal');
    const applyForm = document.getElementById('applyForm');
    const submitBtn = document.getElementById('submitBtn');
    const submitSuccess = document.getElementById('submitSuccess');
    const fileInput = document.getElementById('appResume');
    const fileLabel = document.getElementById('fileLabel');

    function openModal(role) {
        if (!applyModal) return;
        if (modalRole) modalRole.textContent = role || 'General Application';
        applyModal.classList.add('open');
        document.body.style.overflow = 'hidden';
        if (submitSuccess) submitSuccess.classList.remove('show');
        if (applyForm) applyForm.reset();
        if (fileLabel) fileLabel.textContent = 'Click to upload or drag & drop';
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<i class="fa-solid fa-paper-plane"></i> Send Application';
        }
    }

    function closeModalFn() {
        if (!applyModal) return;
        applyModal.classList.remove('open');
        document.body.style.overflow = '';
    }

    document.querySelectorAll('.apply-btn, #generalApplyBtn').forEach(btn => {
        btn.addEventListener('click', () => openModal(btn.dataset.role));
    });

    if (closeModalBtn) closeModalBtn.addEventListener('click', closeModalFn);
    if (applyModal) {
        applyModal.addEventListener('click', (e) => {
            if (e.target === applyModal) closeModalFn();
        });
    }
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && applyModal && applyModal.classList.contains('open')) closeModalFn();
    });

    if (fileInput && fileLabel) {
        fileInput.addEventListener('change', () => {
            fileLabel.textContent = fileInput.files.length > 0
                ? fileInput.files[0].name
                : 'Click to upload or drag & drop';
        });
    }

    if (applyForm) {
        applyForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const data = {
                role: modalRole ? modalRole.textContent : null,
                name: document.getElementById('appName')?.value,
                email: document.getElementById('appEmail')?.value,
                phone: document.getElementById('appPhone')?.value,
                message: document.getElementById('appMessage')?.value,
                resume: fileInput?.files[0]?.name || null
            };
            console.log('Application submitted:', data);

            if (submitBtn) {
                submitBtn.disabled = true;
                submitBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Sending...';
            }

            // Simulated API delay (replace with fetch() to real API later)
            setTimeout(() => {
                if (submitSuccess) submitSuccess.classList.add('show');
                if (submitBtn) submitBtn.innerHTML = '<i class="fa-solid fa-check"></i> Sent!';
                setTimeout(closeModalFn, 2200);
            }, 1200);
        });
    }
});
