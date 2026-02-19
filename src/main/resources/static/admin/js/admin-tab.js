/* === Admin Tab: agents, identity mappings, sessions, audit log === */

const adminTab = {
    _initialized: false,
    _editingAgentId: null,

    init() {
        if (this._initialized) return;
        this._initialized = true;
        this.loadAgents();
        this.loadIdentityMappings();
        this.loadSessions();
        this.loadAuditLog(0);
    },

    /* --- Agents --- */

    async loadAgents() {
        const grid = document.getElementById('agents-grid');
        try {
            const agents = await fetchApi('/admin/api/agents');
            if (!agents || agents.length === 0) {
                grid.innerHTML = '<div class="empty-state">No agents configured yet.</div>';
                this._populateAgentDropdowns([]);
                return;
            }
            grid.innerHTML = agents.map(a => `
                <div class="card">
                    <div class="card-title">${escapeHtml(a.agentId)}</div>
                    <div class="card-subtitle">${escapeHtml(a.displayName || '')}</div>
                    <div class="card-meta">
                        <span class="badge badge-trust">${escapeHtml(a.trustLevel)}</span>
                        ${a.model ? `<span class="badge badge-scope">${escapeHtml(a.model)}</span>` : ''}
                        <span class="badge badge-scope">${a.maxTokensPerRequest} tokens</span>
                    </div>
                    <div class="card-actions">
                        <button class="btn btn-sm btn-outline" onclick="adminTab.editAgent('${escapeHtml(a.agentId)}')">Edit</button>
                        <button class="btn btn-sm btn-danger" onclick="adminTab.deleteAgent('${escapeHtml(a.agentId)}')">Delete</button>
                    </div>
                </div>
            `).join('');
            this._populateAgentDropdowns(agents);
        } catch (e) {
            grid.innerHTML = `<div class="empty-state">Failed to load agents: ${escapeHtml(e.message)}</div>`;
        }
    },

    _populateAgentDropdowns(agents) {
        // Session filter dropdown
        const filter = document.getElementById('session-agent-filter');
        if (filter) {
            const val = filter.value;
            filter.innerHTML = '<option value="">All Agents</option>' +
                agents.map(a => `<option value="${escapeHtml(a.agentId)}">${escapeHtml(a.agentId)}</option>`).join('');
            filter.value = val;
        }
        // Chat agent selector
        const chatSelect = document.getElementById('chat-agent-select');
        if (chatSelect) {
            chatSelect.innerHTML = '<option value="default">default</option>' +
                agents.filter(a => a.agentId !== 'default')
                    .map(a => `<option value="${escapeHtml(a.agentId)}">${escapeHtml(a.agentId)}</option>`)
                    .join('');
        }
    },

    createAgent() {
        this._editingAgentId = null;
        document.getElementById('modal-title').textContent = 'New Agent';
        document.getElementById('form-agentId').value = '';
        document.getElementById('form-agentId').disabled = false;
        document.getElementById('form-displayName').value = '';
        document.getElementById('form-model').value = '';
        document.getElementById('form-trustLevel').value = 'STANDARD';
        document.getElementById('form-systemPrompt').value = '';
        document.getElementById('form-allowedTools').value = '';
        document.getElementById('form-deniedTools').value = '';
        document.getElementById('form-maxTokens').value = '4096';
        document.getElementById('form-maxToolCalls').value = '10';
        document.getElementById('agent-modal').style.display = '';
    },

    async editAgent(agentId) {
        try {
            const agent = await fetchApi(`/admin/api/agents/${encodeURIComponent(agentId)}`);
            if (!agent) return;
            this._editingAgentId = agentId;
            document.getElementById('modal-title').textContent = 'Edit Agent';
            document.getElementById('form-agentId').value = agent.agentId;
            document.getElementById('form-agentId').disabled = true;
            document.getElementById('form-displayName').value = agent.displayName || '';
            document.getElementById('form-model').value = agent.model || '';
            document.getElementById('form-trustLevel').value = agent.trustLevel || 'STANDARD';
            document.getElementById('form-systemPrompt').value = agent.systemPrompt || '';
            document.getElementById('form-allowedTools').value = (agent.allowedTools || []).join(', ');
            document.getElementById('form-deniedTools').value = (agent.deniedTools || []).join(', ');
            document.getElementById('form-maxTokens').value = agent.maxTokensPerRequest || 4096;
            document.getElementById('form-maxToolCalls').value = agent.maxToolCallsPerRequest || 10;
            document.getElementById('agent-modal').style.display = '';
        } catch (e) {
            alert('Failed to load agent: ' + e.message);
        }
    },

    async saveAgent(event) {
        event.preventDefault();
        const agentId = document.getElementById('form-agentId').value.trim();
        if (!agentId) return false;

        const parseList = (val) => val ? val.split(',').map(s => s.trim()).filter(Boolean) : [];

        const body = {
            agentId: agentId,
            displayName: document.getElementById('form-displayName').value.trim() || null,
            model: document.getElementById('form-model').value.trim() || null,
            trustLevel: document.getElementById('form-trustLevel').value,
            systemPrompt: document.getElementById('form-systemPrompt').value || null,
            allowedTools: parseList(document.getElementById('form-allowedTools').value),
            deniedTools: parseList(document.getElementById('form-deniedTools').value),
            maxTokensPerRequest: parseInt(document.getElementById('form-maxTokens').value) || 4096,
            maxToolCallsPerRequest: parseInt(document.getElementById('form-maxToolCalls').value) || 10
        };

        try {
            await fetchApi(`/admin/api/agents/${encodeURIComponent(agentId)}`, {
                method: 'PUT',
                body: JSON.stringify(body)
            });
            this.closeModal();
            this._initialized = false;
            this.init();
        } catch (e) {
            alert('Failed to save agent: ' + e.message);
        }
        return false;
    },

    async deleteAgent(agentId) {
        if (!confirm(`Delete agent "${agentId}"? This cannot be undone.`)) return;
        try {
            await fetchApi(`/admin/api/agents/${encodeURIComponent(agentId)}`, { method: 'DELETE' });
            this._initialized = false;
            this.init();
        } catch (e) {
            alert('Failed to delete agent: ' + e.message);
        }
    },

    closeModal() {
        document.getElementById('agent-modal').style.display = 'none';
    },

    /* --- Identity Mappings --- */

    async loadIdentityMappings() {
        const container = document.getElementById('mappings-list');
        try {
            const mappings = await fetchApi('/admin/api/identity-mappings/pending');
            if (!mappings || mappings.length === 0) {
                container.innerHTML = '<div class="empty-state">No pending identity mappings.</div>';
                return;
            }
            container.innerHTML = mappings.map(m => `
                <div class="card">
                    <div class="card-title">${escapeHtml(m.displayName || m.channelUserId)}</div>
                    <div class="card-subtitle">${escapeHtml(m.channelType)} &mdash; ${escapeHtml(m.channelUserId)}</div>
                    <div class="card-meta">
                        <span class="badge badge-scope">Created ${formatTimestamp(m.createdAt)}</span>
                    </div>
                    <div class="mapping-input">
                        <input type="text" class="input" id="mapping-principal-${m.id}"
                               placeholder="jclaw principal" value="${escapeHtml(m.jclawPrincipal || '')}">
                        <button class="btn btn-sm btn-accent" onclick="adminTab.approveMapping('${m.id}')">Approve</button>
                    </div>
                </div>
            `).join('');
        } catch (e) {
            container.innerHTML = `<div class="empty-state">Failed to load mappings: ${escapeHtml(e.message)}</div>`;
        }
    },

    async approveMapping(id) {
        const input = document.getElementById('mapping-principal-' + id);
        const principal = input ? input.value.trim() : '';
        if (!principal) {
            alert('Please enter a jclaw principal.');
            return;
        }
        try {
            await fetchApi(`/admin/api/identity-mappings/${id}/approve`, {
                method: 'POST',
                body: JSON.stringify({ jclawPrincipal: principal })
            });
            this.loadIdentityMappings();
        } catch (e) {
            alert('Failed to approve mapping: ' + e.message);
        }
    },

    /* --- Sessions --- */

    async loadSessions() {
        const container = document.getElementById('sessions-list');
        const agentFilter = document.getElementById('session-agent-filter').value;
        const url = agentFilter
            ? `/admin/api/sessions?agentId=${encodeURIComponent(agentFilter)}`
            : '/admin/api/sessions';

        try {
            const sessions = await fetchApi(url);
            if (!sessions || sessions.length === 0) {
                container.innerHTML = '<div class="empty-state">No active sessions.</div>';
                return;
            }
            container.innerHTML = sessions.map(s => `
                <div class="card">
                    <div class="card-title">${escapeHtml(s.agentId)}</div>
                    <div class="card-subtitle">${escapeHtml(s.principal)} &mdash; ${escapeHtml(s.channelType)}</div>
                    <div class="card-meta">
                        <span class="badge badge-scope">${escapeHtml(s.scope)}</span>
                        <span class="badge badge-scope">${s.messageCount} msgs</span>
                        <span class="badge badge-scope">${s.totalTokens} tokens</span>
                    </div>
                    <div style="font-size:11px;color:var(--text-muted)">Last active: ${formatTimestamp(s.lastActiveAt)}</div>
                    <div class="card-actions">
                        <button class="btn btn-sm btn-danger" onclick="adminTab.archiveSession('${s.id}')">Archive</button>
                    </div>
                </div>
            `).join('');
        } catch (e) {
            container.innerHTML = `<div class="empty-state">Failed to load sessions: ${escapeHtml(e.message)}</div>`;
        }
    },

    async archiveSession(sessionId) {
        if (!confirm('Archive this session?')) return;
        try {
            await fetchApi(`/admin/api/sessions/${sessionId}/archive`, { method: 'POST' });
            this.loadSessions();
        } catch (e) {
            alert('Failed to archive session: ' + e.message);
        }
    },

    /* --- Audit Log --- */

    _auditPage: 0,
    _auditTotalPages: 0,

    async loadAuditLog(page) {
        const container = document.getElementById('audit-log');
        const principal = document.getElementById('audit-principal').value.trim();
        const eventType = document.getElementById('audit-event-type').value;

        let url = `/admin/api/audit?page=${page}&size=20`;
        if (principal) url += `&principal=${encodeURIComponent(principal)}`;
        if (eventType) url += `&eventType=${encodeURIComponent(eventType)}`;

        try {
            const data = await fetchApi(url);
            this._auditPage = data.number || 0;
            this._auditTotalPages = data.totalPages || 0;

            const events = data.content || [];
            if (events.length === 0) {
                container.innerHTML = '<div class="empty-state">No audit events found.</div>';
                document.getElementById('audit-pagination').innerHTML = '';
                return;
            }

            container.innerHTML = `
                <table class="audit-table">
                    <thead>
                        <tr>
                            <th>Time</th>
                            <th>Type</th>
                            <th>Principal</th>
                            <th>Agent</th>
                            <th>Action</th>
                            <th>Outcome</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${events.map(e => `
                            <tr>
                                <td>${formatTimestamp(e.timestamp)}</td>
                                <td><span class="badge badge-scope">${escapeHtml(e.eventType)}</span></td>
                                <td>${escapeHtml(e.principal || '-')}</td>
                                <td>${escapeHtml(e.agentId || '-')}</td>
                                <td title="${escapeHtml(e.action)}">${escapeHtml(e.action)}</td>
                                <td>${escapeHtml(e.outcome || '-')}</td>
                            </tr>
                        `).join('')}
                    </tbody>
                </table>
            `;

            this._renderAuditPagination();
        } catch (e) {
            container.innerHTML = `<div class="empty-state">Failed to load audit log: ${escapeHtml(e.message)}</div>`;
        }
    },

    _renderAuditPagination() {
        const container = document.getElementById('audit-pagination');
        if (this._auditTotalPages <= 1) {
            container.innerHTML = '';
            return;
        }
        container.innerHTML = `
            <button class="btn btn-sm btn-outline" ${this._auditPage <= 0 ? 'disabled' : ''}
                    onclick="adminTab.loadAuditLog(${this._auditPage - 1})">Prev</button>
            <span class="page-info">Page ${this._auditPage + 1} of ${this._auditTotalPages}</span>
            <button class="btn btn-sm btn-outline" ${this._auditPage >= this._auditTotalPages - 1 ? 'disabled' : ''}
                    onclick="adminTab.loadAuditLog(${this._auditPage + 1})">Next</button>
        `;
    }
};
