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
                    Slack  Teams  Discord  Google Chat  Web Chat  REST API
                      |      |      |         |           |         |
                      v      v      v         v           v         v
                  [ChannelWebhookAuthFilter] -----> [ChannelRouter]
                                                          |
                                              [IdentityMappingService]
                                                          |
                                                    [AgentRuntime]
                                                     /    |    \
                                            [ContentFilter] [ModelRouter] [ToolRegistry]
                                                     |         |              |
                                              [EgressGuard] [ChatModel]  [ToolPolicy]
                                                     |                       |
                                              [SessionManager]    [DataQuery, HttpFetch,
                                                     |             WebSearch, ChannelSend,
                                                  [Redis]          SessionSend, Scheduler]
                                                     |
                                                 [PostgreSQL]
```

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
- An Anthropic API key (or OpenAI)
- SSO provider (for production; local profile skips this)

### Local Development

```bash
# Build
./gradlew build

# Run with local profile (H2 in-memory, no SSO required)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

### Cloud Foundry Deployment

```bash
# Create required services
cf create-service genai standard jclaw-genai
cf create-service p.redis on-demand-cache jclaw-cache
cf create-service postgres on-demand-small jclaw-db
cf create-service p-identity uaa jclaw-sso
cf cups jclaw-secrets -p '{"admin-api-key":"your-key"}'

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

### Configuration

Agents are configured via `jclaw.agents` in `application.yml` or via the Admin API:

```yaml
jclaw:
  agents:
    - id: general
      channels:
        - type: slack
          workspace: T01MYTEAM
          activation: MENTION
        - type: webchat
          activation: ALWAYS
    - id: ops-bot
      channels:
        - type: slack
          workspace: T01MYTEAM
          channels: [C01OPS]
          activation: ALWAYS
```

Agent trust levels control tool access:

| Trust Level | Allowed Tool Risk |
|-------------|-------------------|
| `RESTRICTED` | LOW only |
| `STANDARD` | LOW + MEDIUM |
| `ELEVATED` | LOW + MEDIUM + HIGH |

## API Endpoints

### Chat
- `POST /api/chat/send` — Send a message (returns full response)
- `POST /api/chat/stream` — Send a message (SSE streaming response)

### Web Chat
- `POST /api/webchat/send` — Send message (server-assigned conversation ID)
- `GET /api/webchat/stream/{conversationId}` — SSE stream for responses

### Admin (`SCOPE_jclaw.admin`)
- `GET/PUT /api/admin/agents/{agentId}` — Agent configuration CRUD
- `GET /api/admin/audit` — Query audit log
- `POST /api/admin/identity-mappings/{id}/approve` — Approve identity mapping

### Webhooks
- `POST /webhooks/slack` — Slack Events API
- `POST /webhooks/teams` — Microsoft Bot Framework
- `POST /webhooks/google-chat` — Google Chat push
- `POST /webhooks/discord` — Discord interactions

### Health
- `GET /actuator/health` — Health check
- `GET /actuator/prometheus` — Prometheus metrics

## Security Model

jclaw takes a defense-in-depth approach:

1. **Channel authentication** — Each webhook endpoint verifies signatures using channel-specific cryptography (Slack HMAC-SHA256, Teams/Google Chat JWT with JWKS, Discord Ed25519). Replay protection via timestamp validation.
2. **Identity mapping** — Channel users are mapped to SSO principals through an admin-approved workflow. Unmapped identities are queued, not served.
3. **Trust levels** — Agents have configurable trust levels that enforce hard ceilings on which tool risk levels they can access. Allow/deny lists provide fine-grained control.
4. **Content filtering** — A pipeline of filters runs on every inbound message (pattern detection, instruction injection detection, input sanitization, length enforcement) and on every outbound response (egress guard).
5. **SQL sandboxing** — The data query tool enforces SELECT-only queries with keyword blocklists, schema/table/function blocklists, comment stripping, and connection-level read-only mode.
6. **Egress control** — HTTP fetch operations are validated against per-agent egress allowlists. Redirect following is disabled to prevent SSRF via redirect chains.
7. **Rate limiting** — Redis-backed per-user rate limits on API endpoints.
8. **Audit logging** — Every security-relevant event is persisted with principal, action, outcome, and source IP.

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
