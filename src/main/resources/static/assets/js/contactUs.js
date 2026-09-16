document.addEventListener('DOMContentLoaded', () => {

    // ==========================================
    // 1. HIGHLIGHT TODAY IN OFFICE HOURS
    // ==========================================
    const today = new Date().getDay(); // 0 = Sunday ... 6 = Saturday
    const hoursList = document.getElementById('hoursList');
    if (hoursList) {
        const todayRow = hoursList.querySelector(`li[data-day="${today}"]`);
        if (todayRow) todayRow.classList.add('today');
    }

    // ==========================================
    // 2. FAQ ACCORDION (single-open)
    // ==========================================
    document.querySelectorAll('.faq-question').forEach(btn => {
        btn.addEventListener('click', () => {
            const item = btn.parentElement;
            const answer = item.querySelector('.faq-answer');
            const isOpen = item.classList.contains('open');

            // Close all others (single-open accordion)
            document.querySelectorAll('.faq-item.open').forEach(openItem => {
                openItem.classList.remove('open');
                openItem.querySelector('.faq-answer').style.maxHeight = null;
            });

            if (!isOpen) {
                item.classList.add('open');
                answer.style.maxHeight = answer.scrollHeight + 'px';
            }
        });
    });

    // ==========================================
    // 3. CONTACT FORM SUBMISSION (mock — no backend yet)
    // ==========================================
    const contactForm = document.getElementById('contactForm');
    const contactBtn = document.getElementById('contactSubmitBtn');
    const contactSuccess = document.getElementById('contactSuccess');

    if (contactForm) {
        contactForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const data = {
                name: document.getElementById('cName').value,
                email: document.getElementById('cEmail').value,
                phone: document.getElementById('cPhone').value,
                subject: document.getElementById('cSubject').value,
                message: document.getElementById('cMessage').value
            };
            console.log('Contact form submission:', data);

            contactBtn.disabled = true;
            contactBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Sending...';

            // Simulate network delay — replace with a real fetch() later
            setTimeout(() => {
                contactSuccess.classList.add('show');
                contactBtn.innerHTML = '<i class="fa-solid fa-check"></i> Sent!';
                contactForm.reset();
                setTimeout(() => {
                    contactBtn.disabled = false;
                    contactBtn.innerHTML = '<i class="fa-solid fa-paper-plane"></i> Send Message';
                }, 2500);
            }, 1200);
        });
    }
});
