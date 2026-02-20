/* === Chat Tab: send messages, display conversation === */

const chatTab = {
    _initialized: false,
    _conversationId: null,
    _sending: false,

    init() {
        if (this._initialized) return;
        this._initialized = true;
        this._conversationId = crypto.randomUUID();
        this._setupKeyHandler();
        this.loadModels();
    },

    _setupKeyHandler() {
        const input = document.getElementById('chat-input');
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });
    },

    async loadModels() {
        try {
            const models = await fetchApi('/admin/api/models');
            const select = document.getElementById('chat-model-select');
            const datalist = document.getElementById('available-models');

            if (models && models.length > 0) {
                select.innerHTML = '<option value="">(agent default)</option>' +
                    models.map(m => `<option value="${escapeHtml(m)}">${escapeHtml(m)}</option>`).join('');

                if (datalist) {
                    datalist.innerHTML = models.map(m =>
                        `<option value="${escapeHtml(m)}">`).join('');
                }
            }
        } catch (e) {
            // Model loading is non-critical; agent default will be used
        }
    },

    async sendMessage() {
        if (this._sending) return;

        const input = document.getElementById('chat-input');
        const message = input.value.trim();
        if (!message) return;

        const agentId = document.getElementById('chat-agent-select').value;
        const modelOverride = document.getElementById('chat-model-select').value || null;

        this.addMessage('user', message);
        input.value = '';
        this._sending = true;

        const sendBtn = document.getElementById('chat-send-btn');
        sendBtn.disabled = true;
        sendBtn.textContent = '...';

        try {
            const body = {
                message: message,
                agentId: agentId,
                conversationId: this._conversationId
            };
            if (modelOverride) {
                body.modelOverride = modelOverride;
            }

            const data = await fetchApi('/admin/api/chat/send', {
                method: 'POST',
                body: JSON.stringify(body)
            });
            this.addMessage('assistant', data.response || '(empty response)');
        } catch (e) {
            this.addMessage('error', 'Error: ' + e.message);
        } finally {
            this._sending = false;
            sendBtn.disabled = false;
            sendBtn.textContent = 'Send';
            input.focus();
        }
    },

    addMessage(role, content) {
        const container = document.getElementById('chat-messages');
        const div = document.createElement('div');
        div.className = 'chat-msg ' + role;
        div.textContent = content;
        container.appendChild(div);
        container.scrollTop = container.scrollHeight;
    },

    clearHistory() {
        document.getElementById('chat-messages').innerHTML = '';
        this._conversationId = crypto.randomUUID();
    }
};
