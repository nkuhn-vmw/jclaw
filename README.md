# jclaw

**The AI assistant that actually knows where it lives.**

jclaw is a multi-channel, multi-agent AI assistant platform built on Spring Boot and Spring AI. It connects LLMs to your team's chat platforms — Slack, Microsoft Teams, Discord, Google Chat, and a built-in web chat — with enterprise-grade security, session management, and observability baked in from day one.

Think of it as the connective tissue between your AI models and the places your people actually work.

## What It Does

- **Multi-channel delivery** — One deployment serves Slack, Teams, Discord, Google Chat, a REST API, and a web chat UI simultaneously. Each channel gets its own adapter with proper webhook authentication (HMAC-SHA256, JWT, Ed25519).
- **Multi-agent routing** — Define multiple agents with different personalities, tool sets, trust levels, and model configurations. Route messages to the right agent based on channel bindings, workspace, and activation rules.
- **Streaming responses** — Server-Sent Events for web chat, chunked delivery for channels with size limits. The LLM streams, you stream.
- **Session management** — Conversations persist across messages with configurable scoping (per-user, per-group, per-channel, cross-channel). Sessions auto-compact via LLM summarization when they grow too large.
- **Built-in tool system** — Agents can query databases (read-only, sandboxed), fetch URLs (egress-allowlisted), search the web, send cross-channel messages, read session history, and schedule recurring tasks.
- **Content safety** — Inbound content filtering (prompt injection detection, input sanitization, length enforcement) and outbound egress guarding run on every message. Configurable per-agent policies.
- **Identity mapping** — Maps channel-specific user IDs (Slack member IDs, Teams AAD IDs, etc.) to SSO principals via an admin approval workflow. No unmapped user touches an agent.
- **Full audit trail** — Every message, tool call, auth event, config change, and identity mapping is logged to a queryable audit table with 365-day retention.

## Architecture

```
Slack    Teams    Discord    Google Chat    WebChat    REST API
  │        │         │            │            │          │
  ▼        ▼         ▼            ▼            ▼          ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ChannelWebhookAuthFilter                    │
│           HMAC-SHA256 / JWT+JWKS / Ed25519 signatures           │
└────────────────────────────────┬────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                          ChannelRouter                          │
│               Normalizes messages → InboundMessage              │
└────────────────────────────────┬────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                      IdentityMappingService                     │
│         Channel user ID → SSO principal (admin-approved)        │
└────────────────────────────────┬────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                           AgentRuntime                          │
│                                                                 │
│  ContentFilterChain    ModelRouter          ToolRegistry        │
│  ├ PatternDetector     ├ Anthropic          ├ data_query        │
│  ├ InjectionDetector   ├ OpenAI             ├ http_fetch        │
│  ├ InputSanitizer      └ Tanzu GenAI        ├ web_search        │
│  └ EgressGuard                              ├ channel_send      │
│                                             ├ session_send      │
│                                             ├ session_history   │
│                                             ├ session_list      │
│                                             └ scheduled_task    │
└───────────┬────────────────────┬────────────────────┬───────────┘
            ▼                    ▼                    ▼
      SessionManager          Metrics            AuditService
     ┌────────────┐       ┌────────────┐       ┌────────────┐
     │   Redis    │       │ Prometheus │       │ PostgreSQL │
     └────────────┘       │    OTel    │       └────────────┘
                          └────────────┘
```

### Request Flow

1. A message arrives from any channel (Slack event, Teams webhook, Discord gateway, REST API call, etc.)
2. **ChannelWebhookAuthFilter** verifies the channel-specific signature (HMAC, JWT, or Ed25519) and rejects replays
3. **ChannelRouter** normalizes it into an `InboundMessage` and selects the target agent based on channel bindings
4. **IdentityMappingService** resolves the channel user to an SSO principal — unmapped users are queued for admin approval
5. **ContentFilterChain** runs inbound filters (pattern detection, injection detection, input sanitization, length limits)
6. **AgentRuntime** resolves the ChatModel via ModelRouter, builds the prompt with session history, and calls the LLM
7. If the LLM requests tool calls, **ToolRegistry** checks **ToolPolicy** against the agent's trust level and executes allowed tools
8. **EgressGuard** validates outbound content before delivery
9. The response is sent back through the originating channel adapter
10. **AuditService** logs the entire interaction to PostgreSQL

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | Spring Boot 3.4, Java 21 |
| AI | Spring AI 1.0.0-M6 (Tanzu GenAI, Anthropic, OpenAI) |
| Database | PostgreSQL + Flyway migrations |
| Cache | Redis (reactive, session history caching) |
| Auth | SSO via OAuth2/OIDC, per-channel webhook verification |
| Observability | Micrometer + Prometheus metrics, OpenTelemetry tracing, structured JSON logging |
| Channels | Slack Bolt SDK, JDA (Discord), Microsoft Bot Framework, Google Chat API |
| Deployment | Cloud Foundry (with GenAI, SSO, DB, Redis service bindings) |

## Getting Started

### Prerequisites

- Java 21+
- PostgreSQL
- Redis
- An Anthropic API key (or Tanzu GenAI / OpenAI)
- SSO provider (for production; local profile skips this)

### Local Development

```bash
# Build
./gradlew build

# Run with local profile
SPRING_PROFILES_ACTIVE=local \
  GENAI_API_KEY=sk-ant-... \
  ./gradlew bootRun
```

Set up local services (PostgreSQL + Redis) or use Docker:

```bash
docker run -d --name jclaw-db -p 5432:5432 \
  -e POSTGRES_DB=jclaw -e POSTGRES_USER=jclaw -e POSTGRES_PASSWORD=jclaw \
  postgres:16

docker run -d --name jclaw-redis -p 6379:6379 redis:7
```

### Cloud Foundry Deployment

```bash
# Create required services
cf create-service genai standard jclaw-genai
cf create-service p.redis on-demand-cache jclaw-cache
cf create-service postgres on-demand-small jclaw-db
cf create-service p-identity uaa jclaw-sso

# Create user-provided service for secrets
cf cups jclaw-secrets -p '{
  "admin-api-key": "your-admin-key",
  "slack-bot-token": "xoxb-...",
  "slack-app-token": "xapp-...",
  "discord-bot-token": "MTk3...",
  "teams-app-id": "your-app-id",
  "teams-app-password": "your-app-password",
  "google-chat-credentials": "{...}",
  "search-api-key": "your-search-key"
}'

# Build and push
./gradlew build
cf push
```

### Tanzu GenAI Service Binding

jclaw supports both Tanzu GenAI service binding formats. The GenAI tile is always the primary model provider in the cloud profile — it takes priority over any other configured model providers.

**Endpoint format (multi-model)** — A single service binding exposes multiple models through the GenAI proxy. Models are discovered automatically at startup via the OpenAI-compatible `/v1/models` endpoint. Embedding models are filtered out.

```json
{
  "credentials": {
    "endpoint": {
      "api_base": "https://genai-proxy.example.com/instance-id",
      "api_key": "...",
      "config_url": "https://genai-proxy.example.com/config/..."
    }
  }
}
```

**Direct binding format (single-model)** — Each service binding maps to exactly one model with flat credentials.

```json
{
  "credentials": {
    "api_base": "https://model-endpoint.example.com",
    "api_key": "...",
    "model_name": "my-model"
  }
}
```

Both formats are auto-detected from VCAP_SERVICES. You can mix formats across multiple `genai` service bindings. Agents can target specific models by name in their configuration.

## Usage Examples

### Sending a Chat Message

```bash
# Full response
curl -X POST https://jclaw.example.com/api/chat/send \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What are the current open incidents?",
    "agentId": "ops-bot"
  }'

# Response:
# {
#   "response": "Here are the current open incidents...",
#   "agentId": "ops-bot"
# }
```

### Streaming a Response

```bash
curl -X POST https://jclaw.example.com/api/chat/stream \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message": "Summarize this week'\''s activity", "agentId": "general"}' \
  --no-buffer

# Server-Sent Events stream:
# {"content":"Here","finishReason":"continue","metadata":{}}
# {"content":" is a","finishReason":"continue","metadata":{}}
# {"content":" summary...","finishReason":"stop","metadata":{}}
```

### Using Web Chat

```bash
# Send a message (get back a conversation ID)
CONV_ID=$(curl -s -X POST https://jclaw.example.com/api/webchat/send \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello!"}' | jq -r .conversationId)

# Stream the response via SSE
curl https://jclaw.example.com/api/webchat/stream/$CONV_ID \
  -H "Authorization: Bearer $JWT_TOKEN" \
  --no-buffer

# event: typing
# data:
#
# event: message
# data: Hello! How can I help you today?
```

### Configuring an Agent

```bash
curl -X PUT https://jclaw.example.com/api/admin/agents/support-bot \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "support-bot",
    "displayName": "Support Assistant",
    "model": "openai/gpt-oss-120b",
    "trustLevel": "STANDARD",
    "systemPrompt": "You are a helpful support agent for Acme Corp. Answer questions about our products and services. Be concise and professional.",
    "allowedTools": ["web_search", "http_fetch", "session_history"],
    "deniedTools": [],
    "egressAllowlist": ["https://api\\.acme\\.com/.*", "https://docs\\.acme\\.com/.*"],
    "maxTokensPerRequest": 4096,
    "maxToolCallsPerRequest": 10,
    "contentFilterPolicy": {
      "maxMessageLength": 50000,
      "enablePatternDetection": true,
      "enableInstructionDetection": true,
      "enableEgressGuard": true
    }
  }'
```

### Approving an Identity Mapping

When a user sends a message from Slack/Teams/Discord for the first time, their channel identity is queued for admin approval before they can interact with agents.

```bash
# List pending identity mappings
curl https://jclaw.example.com/api/admin/identity-mappings/pending \
  -H "Authorization: Bearer $JWT_TOKEN"

# Approve a mapping — link the channel user to an SSO principal
curl -X POST https://jclaw.example.com/api/admin/identity-mappings/550e8400-e29b-.../approve \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jclawPrincipal": "jane.doe@acme.com"}'
```

### Querying the Audit Log

```bash
# Recent events for a specific user
curl "https://jclaw.example.com/api/admin/audit?principal=jane.doe@acme.com&size=20" \
  -H "Authorization: Bearer $JWT_TOKEN"

# Filter by event type
curl "https://jclaw.example.com/api/admin/audit?eventType=TOOL_CALL&size=50" \
  -H "Authorization: Bearer $JWT_TOKEN"
```

### Managing Sessions

```bash
# List active sessions for an agent
curl https://jclaw.example.com/api/admin/agents/support-bot/sessions \
  -H "Authorization: Bearer $JWT_TOKEN"

# Archive a session
curl -X POST https://jclaw.example.com/api/admin/sessions/550e8400-.../archive \
  -H "Authorization: Bearer $JWT_TOKEN"
```

## Configuration

### Agent Configuration (application.yml)

Agents are configured via `jclaw.agents` in `application.yml` or via the Admin API at runtime:

```yaml
jclaw:
  genai:
    api-key: ${GENAI_API_KEY:}              # For local profile
    model: claude-sonnet-4-20250514          # Default model for local profile

  agents:
    - id: general
      displayName: General Assistant
      model: openai/gpt-oss-120b            # Target a specific Tanzu GenAI model
      trustLevel: STANDARD
      channels:
        - type: slack
          workspace: T01MYTEAM
          activation: MENTION               # Respond when @mentioned
        - type: webchat
          activation: ALWAYS                 # Always respond in web chat
        - type: rest-api
          activation: ALWAYS

    - id: ops-bot
      displayName: Operations Bot
      model: openai/gpt-oss-120b
      trustLevel: ELEVATED                   # Can use all tool risk levels
      systemPromptRef: ops-bot-prompt
      allowedTools:
        - data_query
        - http_fetch
        - web_search
        - scheduled_task
      egressAllowlist:
        - "https://api\\.pagerduty\\.com/.*"
        - "https://grafana\\.internal\\.com/.*"
      channels:
        - type: slack
          workspace: T01MYTEAM
          channels: [C01OPS, C01INCIDENTS]   # Only respond in these channels
          activation: ALWAYS                 # Respond to every message
```

### Trust Levels

Trust levels enforce hard ceilings on which tool risk levels an agent can access:

| Trust Level | Allowed Tool Risk | Use Case |
|-------------|-------------------|----------|
| `RESTRICTED` | LOW only | Public-facing chatbots, untrusted contexts |
| `STANDARD` | LOW + MEDIUM | Internal assistants, team channels |
| `ELEVATED` | LOW + MEDIUM + HIGH | Ops bots, admin tools, automated workflows |

### Channel Activation Modes

| Mode | Behavior |
|------|----------|
| `ALWAYS` | Respond to every message in bound channels |
| `MENTION` | Only respond when the bot is @mentioned |
| `DIRECT` | Only respond to direct messages |

### Channel Secrets

All channel credentials are stored in the `jclaw-secrets` user-provided service:

| Key | Channel | Description |
|-----|---------|-------------|
| `slack-bot-token` | Slack | Bot user OAuth token (`xoxb-...`) |
| `slack-app-token` | Slack | App-level token for Socket Mode (`xapp-...`) |
| `discord-bot-token` | Discord | Bot token for Gateway connection |
| `teams-app-id` | Teams | Microsoft App ID |
| `teams-app-password` | Teams | Microsoft App client secret |
| `google-chat-credentials` | Google Chat | Service account JSON key |
| `search-api-key` | Web Search | SerpAPI or Brave Search API key |

## API Endpoints

### Chat

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/chat/send` | `jclaw.user` | Send a message, get full response |
| `POST` | `/api/chat/stream` | `jclaw.user` | Send a message, get SSE stream |

### Web Chat

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/webchat/send` | `jclaw.user` | Send message (returns conversation ID) |
| `GET` | `/api/webchat/stream/{id}` | `jclaw.user` | SSE stream for a conversation |

### Admin

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/admin/agents` | `jclaw.admin` | List all agents |
| `GET` | `/api/admin/agents/{id}` | `jclaw.admin` | Get agent config |
| `PUT` | `/api/admin/agents/{id}` | `jclaw.admin` | Create/update agent |
| `DELETE` | `/api/admin/agents/{id}` | `jclaw.admin` | Delete agent |
| `GET` | `/api/admin/agents/{id}/sessions` | `jclaw.operator` | List sessions for agent |
| `POST` | `/api/admin/sessions/{id}/archive` | `jclaw.operator` | Archive a session |
| `GET` | `/api/admin/identity-mappings/pending` | `jclaw.operator` | List pending mappings |
| `POST` | `/api/admin/identity-mappings/{id}/approve` | `jclaw.operator` | Approve mapping |
| `GET` | `/api/admin/audit` | `jclaw.admin` | Query audit log |

### Webhooks (no auth — signature-verified internally)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/webhooks/teams` | Microsoft Bot Framework events |
| `POST` | `/webhooks/google-chat` | Google Chat push events |

Slack and Discord use persistent connections (Socket Mode and Gateway respectively) rather than webhooks.

### Health & Metrics

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/actuator/health` | Health check (public) |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

## Security Model

jclaw takes a defense-in-depth approach:

1. **Channel authentication** — Each webhook endpoint verifies signatures using channel-specific cryptography (Slack HMAC-SHA256, Teams/Google Chat JWT with JWKS, Discord Ed25519). Replay protection via timestamp validation.
2. **Identity mapping** — Channel users are mapped to SSO principals through an admin-approved workflow. Unmapped identities are queued, not served.
3. **SSO scopes** — API access controlled by OAuth2 scopes: `jclaw.admin`, `jclaw.operator`, `jclaw.user`, `jclaw.service`.
4. **Trust levels** — Agents have configurable trust levels that enforce hard ceilings on which tool risk levels they can access. Allow/deny lists provide fine-grained control.
5. **Content filtering** — A pipeline of filters runs on every inbound message (pattern detection, instruction injection detection, input sanitization, length enforcement) and on every outbound response (egress guard).
6. **SQL sandboxing** — The data query tool enforces SELECT-only queries with keyword blocklists, schema/table/function blocklists, comment stripping, and connection-level read-only mode.
7. **Egress control** — HTTP fetch operations are validated against per-agent egress allowlists. Redirect following is disabled to prevent SSRF via redirect chains.
8. **Rate limiting** — Redis-backed per-user rate limits on API endpoints.
9. **Audit logging** — Every security-relevant event is persisted with principal, action, outcome, and source IP.

## Built-in Tools

| Tool | Risk | Description |
|------|------|-------------|
| `data_query` | MEDIUM | Read-only SQL queries against bound databases |
| `http_fetch` | MEDIUM | Fetch URLs (egress-allowlisted, no redirects) |
| `web_search` | LOW | Web search via SerpAPI or Brave Search |
| `channel_send` | MEDIUM | Send messages to other channels/conversations |
| `session_send` | MEDIUM | Cross-agent session messaging |
| `session_history` | LOW | Read conversation history |
| `session_list` | LOW | List active sessions |
| `scheduled_task` | MEDIUM | Create/manage recurring scheduled messages |

## Observability

- **Metrics**: `jclaw.messages.received`, `jclaw.messages.processed`, `jclaw.llm.requests`, `jclaw.llm.tokens.input/output`, `jclaw.sessions.opened/closed`, `jclaw.tools.calls` — all tagged by agent, channel, and model
- **Tracing**: OpenTelemetry spans on session resolution, agent processing, and LLM calls via `@Observed`
- **Logging**: Structured JSON (cloud profile) with correlation IDs, PII redaction, and MDC context propagation
- **Health**: Custom health indicators for GenAI model connectivity and channel adapter status

## Project Structure

```
src/main/java/com/jclaw/
  agent/          # AgentRuntime, ModelRouter, PromptTemplateService, AgentConfig
  audit/          # AuditService, AuditEvent, AuditRepository
  channel/        # ChannelRouter, adapters (Slack, Teams, Discord, Google Chat, WebChat, REST)
  config/         # JclawProperties, SecretsConfig, GenAiConfig, Redis, Scheduling
  content/        # ContentFilterChain, EgressGuard, PatternDetector, InputSanitizer
  observability/  # JclawMetrics, CorrelationIdFilter, PiiRedactionConverter, HealthIndicators
  security/       # SsoSecurityConfig, ChannelWebhookAuthFilter, IdentityMappingService, RateLimiting
  session/        # SessionManager, CompactionService, Session entities, Flyway migrations
  tool/           # ToolRegistry, ToolPolicy, built-in tools (DataQuery, HttpFetch, WebSearch, etc.)
```

## License

Proprietary. All rights reserved.
