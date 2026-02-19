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

    async sendMessage() {
        if (this._sending) return;

        const input = document.getElementById('chat-input');
        const message = input.value.trim();
        if (!message) return;

        const agentId = document.getElementById('chat-agent-select').value;

        this.addMessage('user', message);
        input.value = '';
        this._sending = true;

        const sendBtn = document.getElementById('chat-send-btn');
        sendBtn.disabled = true;
        sendBtn.textContent = '...';

        try {
            const data = await fetchApi('/admin/api/chat/send', {
                method: 'POST',
                body: JSON.stringify({
                    message: message,
                    agentId: agentId,
                    conversationId: this._conversationId
                })
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
