/* === Skills Tab: display registered tools === */

const skillsTab = {
    _initialized: false,

    async init() {
        if (this._initialized) return;
        this._initialized = true;

        const grid = document.getElementById('skills-grid');
        try {
            const skills = await fetchApi('/admin/api/skills');
            if (!skills || skills.length === 0) {
                grid.innerHTML = '<div class="empty-state">No tools registered.</div>';
                return;
            }
            grid.innerHTML = skills.map(s => `
                <div class="card">
                    <div class="card-title">${escapeHtml(s.name)}</div>
                    <div class="card-subtitle">${escapeHtml(s.description)}</div>
                    <div class="card-meta">
                        <span class="badge badge-risk-${escapeHtml(s.riskLevel)}">${escapeHtml(s.riskLevel)}</span>
                        ${s.requiresApproval ? '<span class="badge badge-approval">Requires Approval</span>' : ''}
                    </div>
                </div>
            `).join('');
        } catch (e) {
            grid.innerHTML = `<div class="empty-state">Failed to load skills: ${escapeHtml(e.message)}</div>`;
        }
    }
};
