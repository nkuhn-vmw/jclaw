# jclaw — Technical Specification

**A Streamlined, Cloud Foundry-Native AI Assistant Built on Spring Boot & Spring AI**

**Version:** 1.0-DRAFT
**Date:** February 16, 2026
**Author:** Nick (CF Weekly / VMware Tanzu Engineering)

---

## Part I — OpenClaw Feature Analysis & Enterprise Security Assessment

### 1. OpenClaw Feature Inventory

OpenClaw (196k stars, MIT license) is a personal AI assistant built in TypeScript/Node.js that acts as a local-first gateway connecting LLMs to real messaging surfaces. Below is a categorized breakdown of every major subsystem.

#### 1.1 Core Platform

| Subsystem | Description | Enterprise Relevance |
|---|---|---|
| **Gateway (WS Control Plane)** | Single long-lived Node.js daemon. WebSocket-based JSON RPC protocol on port 18789. Sessions, presence, config, cron, webhooks. | HIGH — central orchestrator pattern is reusable |
| **Pi Agent Runtime** | RPC-mode agent with tool streaming, block streaming, and per-session context management. | HIGH — the core LLM interaction loop |
| **Session Model** | `main` session for direct chats, per-group isolation, activation modes (mention/always), queue modes, reply-back routing. | HIGH — multi-tenant session isolation is critical |
| **Media Pipeline** | Image/audio/video handling, transcription hooks, size caps, temp file lifecycle. | MEDIUM — useful but not day-one |
| **CLI Surface** | `openclaw gateway`, `openclaw agent`, `openclaw send`, wizard, doctor. | LOW — CF apps are headless |
| **Control UI + WebChat** | SPA served from the Gateway. Dashboard, debug tools, WebChat. | MEDIUM — useful for ops |

#### 1.2 Channel Integrations (16 Channels)

WhatsApp (Baileys), Telegram (grammY), Slack (Bolt), Discord (discord.js), Google Chat, Signal (signal-cli), BlueBubbles/iMessage, Microsoft Teams, Matrix, Zalo, Zalo Personal, WebChat, macOS, iOS, Android.

**Enterprise Assessment:** The multi-channel approach is the core value proposition. For a CF-native deployment, we scope to channels accessible over HTTP/HTTPS without local device pairing: **Slack, Microsoft Teams, Google Chat, Discord, WebChat, and a REST API**. Consumer channels (WhatsApp/Signal/iMessage) require local device sessions and are out of scope.

#### 1.3 Tools & Automation

| Tool | Description | jclaw Scope |
|---|---|---|
| **Browser Control** | Dedicated Chromium via CDP, snapshots, actions, uploads, profiles. | OUT — heavy, security risk |
| **Canvas + A2UI** | Agent-driven visual workspace. macOS/iOS rendering. | OUT — platform-specific |
| **Nodes** | Camera snap/clip, screen record, location, notifications. | OUT — device-specific |
| **Cron + Wakeups** | Scheduled agent tasks. | IN — Spring @Scheduled |
| **Webhooks** | Inbound HTTP triggers. | IN — Spring REST controllers |
| **Gmail Pub/Sub** | Email event triggers. | FUTURE — via CF service binding |
| **Skills Platform** | Bundled/managed/workspace skills with install gating. | IN — simplified as "tools" |
| **Sessions Tools** | Cross-session messaging: `sessions_list`, `sessions_history`, `sessions_send`. | IN — multi-agent coordination |

#### 1.4 Runtime & Safety

- Channel routing and retry policy with configurable streaming/chunking
- Presence and typing indicators
- Model failover (OAuth vs API key rotation) and session pruning
- Multi-agent routing: route channels/accounts/peers to isolated agents with per-agent sessions and workspaces

#### 1.5 Ops & Packaging

- Docker and Nix support
- Tailscale Serve/Funnel for secure remote access
- `openclaw doctor` for migrations and diagnostics
- Structured logging with sensitive-field redaction

---

### 2. Enterprise Security Deep-Dive

This section analyzes every security-relevant feature in OpenClaw, assesses its enterprise readiness, and maps it to jclaw's Cloud Foundry-native design.

#### 2.1 Threat Model Assessment

OpenClaw's documented threat model is explicit and honest:

> "Running an AI agent with shell access on your machine is… spicy."

The threat model identifies three axes: **who can talk to the bot**, **where the bot can act**, and **what the bot can touch**. This translates directly to enterprise concerns around authentication, authorization, and blast-radius containment.

**OpenClaw Threat Vectors:**

| Vector | OpenClaw Mitigation | Enterprise Gap |
|---|---|---|
| **Untrusted inbound DMs** | DM pairing codes, allowlists, `dmPolicy` enum (pairing/allowlist/open/disabled) | Pairing is manual CLI-based. Enterprise needs IdP-backed identity. |
| **Prompt injection** | Model choice guidance, sandbox isolation, tool deny-lists | No runtime prompt-injection detection/filtering layer. Relies on model robustness. |
| **Shell execution** | `system.run` via node pairing + exec approvals. Sandbox mode for non-main sessions. | Host-level exec is default for the main session. No process-level sandboxing without Docker. |
| **Credential leakage** | File permissions (700/600), `detect-secrets` scanning, `logging.redactSensitive` | Credentials stored on local filesystem in JSON. No vault integration, no rotation automation. |
| **Network exposure** | Default loopback bind, Tailscale Serve/Funnel, Gateway auth tokens | Single shared token or password for all WS clients. No per-user/per-role auth. |
| **Session transcript leakage** | Filesystem permissions, session pruning | Plaintext JSONL on disk. No encryption at rest, no access auditing. |
| **Plugin/extension risk** | Runs in-process. Manual trust/review. | No signature verification, no isolation, no plugin permission model. |
| **mDNS information disclosure** | Configurable modes (minimal/off/full) | Broadcasts infrastructure details on LAN by default. |
| **Browser profile takeover** | Dedicated profile recommendation, relay auth | CDP gives full browser control. Chrome extension relay can act as the user. |

#### 2.2 Security Features — Strengths

1. **DM Access Model (4-tier policy):** The `pairing → allowlist → open → disabled` hierarchy is well-designed. The pairing handshake with expiring codes and per-channel caps prevents enumeration attacks.

2. **Per-Agent Access Profiles:** Multi-agent routing with isolated sandboxes, per-agent tool allow/deny lists, and workspace access controls (none/ro/rw) provides genuine defense-in-depth.

3. **Sandbox Architecture:** Docker-based per-session sandboxes with configurable scope (agent/session/shared) and workspace access controls.

4. **Security Audit CLI:** `openclaw security audit --deep` is a genuine security posture assessment tool that checks inbound access, tool blast radius, network exposure, disk hygiene, plugins, and policy drift.

5. **Secret Scanning in CI:** `detect-secrets` integration with baseline tracking prevents accidental credential commits.

6. **Logging Redaction:** Configurable `redactSensitive` with custom `redactPatterns` for tool summaries and log output.

7. **Trusted Proxy Auth:** Proper `X-Forwarded-For` handling with configurable `trustedProxies` list and header validation.

#### 2.3 Security Features — Enterprise Gaps

| Gap | Severity | Impact | jclaw Mitigation |
|---|---|---|---|
| **No IAM/IdP integration** | CRITICAL | No SAML/OIDC/LDAP. Auth is shared tokens/passwords or Tailscale identity headers. | CF SSO tile (`p-identity`) with external IdP federation |
| **No RBAC** | CRITICAL | Owner vs everyone. No role-based permissions, no group-based access. | Custom OAuth2 scopes via SSO tile → Spring Security authorities |
| **No audit trail** | HIGH | No structured audit log for who did what, when. Session transcripts are functional, not compliance artifacts. | PostgreSQL audit_log table with structured events |
| **No encryption at rest** | HIGH | Credentials, sessions, and transcripts stored as plaintext files. Relies on OS-level FDE. | CF-managed service encryption (platform-level) |
| **No secret management integration** | HIGH | No HashiCorp Vault, no CredHub, no KMS. API keys in JSON config files. | CredHub service bindings via VCAP_SERVICES |
| **No rate limiting** | MEDIUM | No per-user or per-channel rate limits beyond pairing code caps. | Redis-backed per-scope rate limiting |
| **No input sanitization layer** | MEDIUM | Prompt injection resistance delegated entirely to the model. No pre-processing or content filtering. | Multi-layer ContentFilterChain |
| **No SOC 2 / compliance controls** | HIGH | No data retention policies, no data residency controls, no PII handling framework. | Configurable retention, PII redaction, audit log |
| **Single-process architecture** | MEDIUM | Gateway crash loses all sessions. No HA, no failover, no horizontal scaling. | Stateless CF app with `cf scale -i N` |
| **No network policy enforcement** | MEDIUM | Tools can make arbitrary outbound network calls. No egress filtering. | Per-agent egress allowlists for http_fetch |

#### 2.4 Formal Verification Reference

OpenClaw documents formal security models at `/security/formal-verification`. This is unusual and commendable for an open-source project — it indicates the maintainers take the threat model seriously, even if the enforcement is soft (model-dependent rather than cryptographic).

#### 2.5 Key Security Lessons for jclaw

From OpenClaw's own "Lessons Learned" section:

1. **The `find ~` incident:** An agent dumped the entire home directory to a group chat. Lesson: filesystem access must be deny-by-default with explicit allowlists.

2. **The "Find the Truth" social engineering attack:** An external user manipulated the agent into exploring the filesystem by creating distrust. Lesson: system prompts are soft barriers; hard enforcement must come from tool policy and sandboxing.

3. **Content-as-attack-vector:** Prompt injection does not require public DMs — web search results, fetched URLs, email content, and attachments can all carry adversarial instructions. Lesson: treat all external content as untrusted, even when the sender is trusted.

---

## Part II — jclaw Technical Specification

### 3. Project Overview

**jclaw** is a streamlined, enterprise-grade AI assistant designed as a Cloud Foundry-native application. It reimagines OpenClaw's core value proposition — a personal AI assistant that connects to your communication channels — but built from the ground up for the Java/Spring ecosystem with Cloud Foundry marketplace services as first-class dependencies.

#### 3.1 Design Principles

1. **CF-Native:** No local state. All persistence via CF marketplace services. `cf push` is the deployment mechanism. VCAP_SERVICES is the configuration source.
2. **Spring Ecosystem:** Spring Boot 3.4+, Spring AI 1.0+, Spring Security 6, Spring WebFlux (reactive), Spring Data.
3. **GenAI Service Integration:** Primary LLM access through the CF GenAI service broker. No direct API key management for LLM providers.
4. **Minimal & Auditable:** Fewer features than OpenClaw, but every feature is enterprise-hardened with proper auth, audit, and blast-radius controls.
5. **12-Factor Compliant:** Config via environment, stateless processes, disposable containers, dev/prod parity.

#### 3.2 Scope Comparison

| OpenClaw Feature | jclaw Status | Rationale |
|---|---|---|
| Gateway WS control plane | **REPLACED** → Spring WebFlux + SSE/WebSocket | Cloud-native reactive stack |
| 16 messaging channels | **REDUCED** → Slack, Teams, Google Chat, Discord, WebChat, REST API | HTTP-accessible enterprise channels only |
| Pi agent runtime (RPC) | **REPLACED** → Spring AI ChatClient with tool calling | Native Spring AI integration |
| Local filesystem tools | **REMOVED** | CF apps have ephemeral filesystems |
| Browser control | **REMOVED** | Security risk, not enterprise-appropriate |
| Canvas + device nodes | **REMOVED** | Platform-specific, not CF-compatible |
| Docker sandboxing | **REPLACED** → CF container isolation + service bindings | CF provides process isolation natively |
| Skills platform | **REPLACED** → Spring-managed tool registry | Type-safe Java tool definitions |
| Cron/webhooks | **KEPT** → Spring @Scheduled + REST controllers | Natural Spring patterns |
| Session management | **KEPT** → Spring Session + Redis/PostgreSQL | Externalized session state |
| Multi-agent routing | **KEPT** → Per-tenant agent configuration | Core enterprise requirement |
| Model failover | **KEPT** → Spring AI model routing | Via GenAI service broker |
| Security audit CLI | **REPLACED** → Spring Actuator + custom health indicators | CF-native health checks |

---

### 4. Architecture

#### 4.1 High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                     Cloud Foundry Platform                         │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    jclaw Application (N instances)            │  │
│  │                                                              │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐   │  │
│  │  │  Channel     │  │  Agent       │  │  Tool             │   │  │
│  │  │  Adapters    │  │  Runtime     │  │  Registry         │   │  │
│  │  │             │  │  (Spring AI) │  │  (Spring Beans)   │   │  │
│  │  │  - Slack    │  │              │  │                   │   │  │
│  │  │  - Teams    │  │  ChatClient  │  │  - WebSearch      │   │  │
│  │  │  - GChat    │  │  + Tools     │  │  - HttpFetch      │   │  │
│  │  │  - Discord  │  │  + Memory    │  │  - DataQuery      │   │  │
│  │  │  - WebChat  │  │  + Routing   │  │  - ScheduledTask  │   │  │
│  │  │  - REST API │  │              │  │  - ChannelSend    │   │  │
│  │  └──────┬──────┘  └──────┬───────┘  └────────┬──────────┘   │  │
│  │         │                │                    │              │  │
│  │  ┌──────┴────────────────┴────────────────────┴──────────┐   │  │
│  │  │              Spring Security Filter Chain              │   │  │
│  │  │  OAuth2/OIDC ─► RBAC ─► Rate Limiting ─► Audit Log   │   │  │
│  │  └───────────────────────────┬────────────────────────────┘   │  │
│  └──────────────────────────────┼────────────────────────────────┘  │
│                                 │                                    │
│  ┌──────────────────────────────┼────────────────────────────────┐  │
│  │              CF Marketplace Services                          │  │
│  │                                                              │  │
│  │  ┌──────────┐  ┌──────────┐  ┌────────┐  ┌──────────────┐   │  │
│  │  │ GenAI    │  │ Postgres │  │ Redis  │  │ CredHub /    │   │  │
│  │  │ Service  │  │          │  │        │  │ Vault        │   │  │
│  │  │          │  │ Sessions │  │ Cache  │  │              │   │  │
│  │  │ LLM      │  │ Audit    │  │ PubSub │  │ Secrets      │   │  │
│  │  │ Access   │  │ Config   │  │ Rate   │  │ Management   │   │  │
│  │  │          │  │ History  │  │ Limits │  │              │   │  │
│  │  └──────────┘  └──────────┘  └────────┘  └──────────────┘   │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

#### 4.2 Component Breakdown

**4.2.1 Channel Adapter Layer (`com.jclaw.channel`)**

Each channel adapter implements a common `ChannelAdapter` interface and is managed as a Spring bean. Channel adapters handle inbound message reception and outbound message delivery, translating between channel-specific wire formats and jclaw's internal `Message` domain model.

```java
public interface ChannelAdapter {
    String channelType();                        // "slack", "teams", etc.
    Flux<InboundMessage> receiveMessages();      // reactive inbound stream
    Mono<Void> sendMessage(OutboundMessage msg); // delivery
    Mono<Void> sendTypingIndicator(String conversationId);
    boolean supportsThreading();
    boolean supportsReactions();
}
```

Supported adapters:

| Adapter | Integration Method | Library |
|---|---|---|
| **Slack** | Bolt for Java (Socket Mode) | `com.slack.api:bolt-socket-mode` |
| **Microsoft Teams** | Bot Framework SDK for Java | `com.microsoft.bot:bot-builder` |
| **Google Chat** | Google Chat API (HTTP push) | `com.google.apis:google-api-services-chat` |
| **Discord** | JDA (Java Discord API) | `net.dv8tion:JDA` |
| **WebChat** | Spring WebSocket/SSE | Built-in |
| **REST API** | Spring WebFlux controllers | Built-in |

**4.2.2 Agent Runtime (`com.jclaw.agent`)**

The agent runtime is the core LLM interaction loop, built on Spring AI's `ChatClient`.

```java
@Service
public class AgentRuntime {

    private final ChatClient.Builder chatClientBuilder;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final PromptTemplateService promptService;
    private final ContentFilterChain contentFilterChain;

    public Flux<AgentResponse> processMessage(
            AgentContext context,
            InboundMessage message) {

        // 1. Resolve session (create or resume)
        Session session = sessionManager.resolveSession(context, message);

        // 2. Apply content filtering (prompt injection detection)
        contentFilterChain.filterInbound(message);

        // 3. Build prompt with system instructions + session history
        Prompt prompt = promptService.buildPrompt(context, session, message);

        // 4. Resolve available tools for this agent/session
        List<ToolCallback> tools = toolRegistry.resolveTools(context);

        // 5. Execute LLM call with streaming
        return chatClientBuilder.build()
            .prompt(prompt)
            .tools(tools)
            .stream()
            .chatResponse()
            .map(this::toAgentResponse);
    }
}
```

**4.2.3 Tool Registry (`com.jclaw.tool`)**

Tools are Spring beans annotated with custom metadata. The registry resolves which tools are available per agent/session based on the agent's tool policy.

```java
@JclawTool(
    name = "web_search",
    description = "Search the web for current information",
    riskLevel = RiskLevel.LOW,
    requiresApproval = false
)
public class WebSearchTool implements ToolCallback {
    // ...
}
```

Built-in tools for v1:

| Tool | Risk Level | Description |
|---|---|---|
| `web_search` | LOW | Web search via configurable provider |
| `http_fetch` | MEDIUM | Fetch URL content (egress allowlist enforced) |
| `data_query` | MEDIUM | Query bound database services |
| `scheduled_task` | LOW | Create/manage scheduled tasks |
| `channel_send` | MEDIUM | Send messages to other channels |
| `session_list` | LOW | List active sessions |
| `session_history` | LOW | Retrieve session transcript |
| `session_send` | MEDIUM | Cross-session agent messaging |

**4.2.4 Security Layer (`com.jclaw.security`)**

This is the most critical divergence from OpenClaw. jclaw replaces OpenClaw's file-based token/pairing model with a full enterprise security stack.

---

### 5. Security Architecture (Enterprise-Grade)

#### 5.1 Authentication via CF SSO Tile (Single Sign-On for VMware Tanzu)

jclaw uses the **Single Sign-On for VMware Tanzu** tile (`p-identity` service) as its identity layer. This is the platform-native approach for TAS foundations — the SSO tile acts as an OAuth2/OIDC authorization server backed by UAA, with support for external IdP federation (SAML and OIDC) to enterprise providers like Okta, Azure AD/Entra ID, PingFederate, ADFS, Google Cloud, and plan-to-plan OIDC.

**5.1.1 SSO Service Instance & Binding**

The operator creates an SSO service plan in the SSO tile dashboard, configures the backing identity provider(s), and then the developer creates a service instance and binds it to jclaw:

```bash
# Create SSO service instance from the operator-configured plan
cf create-service p-identity jclaw-sso-plan jclaw-sso

# Bind to the application
cf bind-service jclaw jclaw-sso

# Restage to pick up VCAP_SERVICES credentials
cf restage jclaw
```

After binding, the SSO tile injects the following into `VCAP_SERVICES`:

```json
{
  "p-identity": [{
    "name": "jclaw-sso",
    "credentials": {
      "auth_domain": "https://jclaw-sso.login.sys.example.com",
      "client_id": "auto-generated-client-id",
      "client_secret": "auto-generated-client-secret",
      "issuer": "https://jclaw-sso.login.sys.example.com/oauth/token"
    }
  }]
}
```

**5.1.2 Spring Boot Auto-Configuration via java-cfenv**

jclaw uses the `java-cfenv-boot-pivotal-sso` library to auto-configure Spring Security OAuth2 from the SSO service binding. This library reads `VCAP_SERVICES` and maps the SSO credentials directly to Spring Security's OAuth2 client and resource server properties — no manual `application.yml` OAuth2 configuration needed in the `cloud` profile.

```java
// Auto-configured by java-cfenv-boot-pivotal-sso:
// spring.security.oauth2.client.registration.sso.client-id       → from VCAP
// spring.security.oauth2.client.registration.sso.client-secret    → from VCAP
// spring.security.oauth2.client.provider.sso.issuer-uri           → from VCAP
// spring.security.oauth2.resourceserver.jwt.issuer-uri            → from VCAP
```

For local development (non-CF), jclaw falls back to explicit configuration:

```yaml
# application-local.yml (dev profile only)
spring:
  security:
    oauth2:
      client:
        registration:
          sso:
            client-id: ${SSO_CLIENT_ID}
            client-secret: ${SSO_CLIENT_SECRET}
            scope: openid,profile,email,roles,jclaw.admin,jclaw.user
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        provider:
          sso:
            issuer-uri: ${SSO_ISSUER_URI}
      resourceserver:
        jwt:
          issuer-uri: ${SSO_ISSUER_URI}
```

**5.1.3 SSO App Configuration in the Tile Dashboard**

The jclaw SSO app must be configured in the SSO Developer Dashboard with:

| Setting | Value | Notes |
|---|---|---|
| **App Type** | Web App (Authorization Code grant) | For WebChat admin UI and human users |
| **App Type** | Service-to-Service (Client Credentials grant) | For REST API / machine clients |
| **Scopes** | `openid`, `profile`, `email`, `roles` | Standard OIDC scopes |
| **Custom Scopes** | `jclaw.admin`, `jclaw.operator`, `jclaw.user` | Mapped to jclaw RBAC roles |
| **Redirect URIs** | `https://jclaw.apps.example.com/login/oauth2/code/sso` | Authorization code callback |
| **Resource Permissions** | `jclaw.admin`, `jclaw.operator`, `jclaw.user` | Resource scopes for token audience |
| **Token Expiration** | 3600s (access), 43200s (refresh) | Configurable per plan |
| **Identity Providers** | Linked from plan (Okta, Azure AD, LDAP, etc.) | Operator-managed federation |

**5.1.4 SSO Security Configuration**

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SsoSecurityConfig {

    @Bean
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        return http
            // OAuth2 Login for WebChat admin UI (authorization code flow)
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("/admin/dashboard")
                .failureUrl("/login?error=true")
            )
            // OAuth2 Resource Server for API endpoints (JWT bearer tokens)
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jclawJwtConverter()))
            )
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/webhooks/**").permitAll() // channel webhooks use own auth
                // Admin UI (authorization code flow)
                .requestMatchers("/admin/**").hasAuthority("SCOPE_jclaw.admin")
                .requestMatchers("/operator/**").hasAnyAuthority(
                    "SCOPE_jclaw.admin", "SCOPE_jclaw.operator")
                // API endpoints (JWT bearer token)
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_jclaw.admin")
                .requestMatchers("/api/agents/*/sessions/**").hasAnyAuthority(
                    "SCOPE_jclaw.operator", "SCOPE_jclaw.admin")
                .requestMatchers("/api/chat/**").hasAnyAuthority(
                    "SCOPE_jclaw.user", "SCOPE_jclaw.admin")
                .anyRequest().authenticated()
            )
            .addFilterBefore(
                new ChannelWebhookAuthFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new AuditLogFilter(), AuthorizationFilter.class)
            .addFilterAfter(new RateLimitFilter(), AuditLogFilter.class)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS) // for API
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**", "/webhooks/**") // APIs use tokens
            )
            .build();
    }

    /**
     * Maps SSO JWT claims to Spring Security authorities.
     * The SSO tile puts granted scopes in the "scope" claim of the JWT.
     * Custom scopes (jclaw.admin, jclaw.operator, jclaw.user) become authorities.
     */
    @Bean
    public JwtAuthenticationConverter jclawJwtConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthorities =
            new JwtGrantedAuthoritiesConverter();
        grantedAuthorities.setAuthoritiesClaimName("scope");
        grantedAuthorities.setAuthorityPrefix("SCOPE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthorities);
        converter.setPrincipalClaimName("user_name"); // UAA puts username here
        return converter;
    }
}
```

**5.1.5 Token Validation**

The SSO tile issues JWTs signed by UAA. jclaw validates tokens by:

1. Verifying the JWT signature against the SSO tile's JWKS endpoint (auto-discovered via `issuer-uri`).
2. Checking `aud` matches jclaw's client ID.
3. Checking `iss` matches the SSO auth domain pattern: `https://<AUTH-DOMAIN>.uaa.<SYSTEM-DOMAIN>/oauth/token`.
4. Verifying token expiry (`exp`) has not passed.
5. Extracting `scope` claims to derive jclaw RBAC authorities.

**5.1.6 External IdP Federation**

The SSO tile supports federated identity via operator-configured external IdPs. jclaw does not need to know about the backing IdP — the SSO tile handles federation transparently. Supported federations include:

- **SAML 2.0:** ADFS, Okta, PingFederate, Azure AD/Entra ID, SiteMinder
- **OIDC:** Okta, Azure AD/Entra ID, Google Cloud, PingOne Cloud, Plan-to-Plan
- **LDAP:** Via UAA LDAP integration on the SSO plan

For multi-IdP deployments, the SSO tile supports **Identity Provider Discovery** via login hints, allowing jclaw to redirect users to the correct IdP based on email domain or other criteria:

```java
@GetMapping("/login")
public String loginWithHint(@RequestParam(required = false) String domain) {
    if (domain != null) {
        String originKey = idpMappingService.resolveOriginKey(domain);
        String loginHint = URLEncoder.encode(
            "{\"origin_key\":\"" + originKey + "\"}", StandardCharsets.UTF_8);
        return "redirect:/oauth2/authorization/sso?login_hint=" + loginHint;
    }
    return "redirect:/oauth2/authorization/sso";
}
```

**5.1.7 Channel-Level Identity Mapping**

Channel adapters authenticate inbound messages using the channel's native identity (Slack user ID, Teams AAD object ID, etc.) and map it to a jclaw principal (the SSO/UAA `user_name`) via the identity mapping table. This bridges external channel identities to the SSO-authenticated enterprise identity.

```java
@Entity
@Table(name = "identity_mappings")
public class IdentityMapping {
    @Id private UUID id;
    private String channelType;       // "slack", "teams", etc.
    private String channelUserId;     // native channel user ID
    private String jclawPrincipal;    // SSO/UAA user_name
    private String displayName;
    private Instant createdAt;
    private Instant lastSeenAt;
    private boolean approved;         // admin must approve new mappings
    private String approvedBy;        // SSO principal who approved
}
```

Admins (with `jclaw.admin` scope) approve identity mappings via the admin UI or API. Until approved, messages from unmapped channel identities are queued but not processed — similar to OpenClaw's pairing model but backed by enterprise identity.

**5.1.8 Service-to-Service Authentication (Client Credentials)**

For machine-to-machine API access (CI/CD pipelines, other CF apps, automation), jclaw supports the OAuth2 Client Credentials grant via the SSO tile. A separate SSO app registration with type "Service-to-Service" is created, and the calling service obtains a token directly from the SSO token endpoint:

```bash
# External service obtains a token
curl -X POST https://jclaw-sso.login.sys.example.com/oauth/token \
  -d "grant_type=client_credentials&client_id=<SVC_CLIENT_ID>&client_secret=<SVC_SECRET>" \
  -H "Content-Type: application/x-www-form-urlencoded"

# Use the token to call jclaw API
curl -H "Authorization: Bearer <token>" \
  https://jclaw.apps.example.com/api/chat/send
```

For CF-internal service-to-service calls, jclaw also supports **container-to-container networking** with mTLS via CF's internal routes (`apps.internal`), providing transport-level authentication in addition to the OAuth2 token.

#### 5.2 Authorization (RBAC via SSO Scopes)

jclaw implements role-based access control using custom OAuth2 scopes defined in the SSO tile. Scopes are assigned to users or groups in the SSO service plan's identity provider configuration, and they appear in the JWT `scope` claim after authentication.

| Role | SSO Scope | Permissions |
|---|---|---|
| `ADMIN` | `jclaw.admin` | Full system configuration. Manage agents, tools, channels, identity mappings. View audit logs. Approve identity mappings. |
| `OPERATOR` | `jclaw.operator` | Manage agent sessions, view transcripts, approve identity mappings. Cannot modify system config. |
| `USER` | `jclaw.user` | Interact with agents via channels. Scoped to their own sessions. |
| `SERVICE` | `jclaw.service` | Machine-to-machine API access (client credentials). Scoped to specific agent(s). |

Scopes are defined as **resources** in the SSO Developer Dashboard and granted to users via group mappings in the backing IdP. For example, with Okta as the external IdP, the Okta admin maps Okta groups to SSO scopes:

- Okta group `jclaw-admins` → SSO scope `jclaw.admin`
- Okta group `jclaw-ops` → SSO scope `jclaw.operator`
- Okta group `jclaw-users` → SSO scope `jclaw.user`

Method-level security is enforced using Spring Security's `@PreAuthorize`:

```java
@Service
public class AgentConfigService {

    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public AgentConfig updateAgentConfig(String agentId, AgentConfig config) {
        auditService.log("CONFIG_CHANGE", agentId, config);
        return agentConfigRepository.save(config);
    }

    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public void approveIdentityMapping(UUID mappingId) {
        // ...
    }

    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.user', 'SCOPE_jclaw.admin')")
    public Session getMySession(String principal) {
        return sessionRepository.findByPrincipal(principal);
    }
}
```

#### 5.3 Per-Agent Tool Policies

Each agent has an explicit tool policy that defines its blast radius. This replaces OpenClaw's sandbox/elevated/tool-deny model with a declarative, auditable configuration.

```java
@Entity
@Table(name = "agent_configs")
public class AgentConfig {
    @Id private String agentId;
    private String displayName;

    @Enumerated(EnumType.STRING)
    private AgentTrustLevel trustLevel;   // RESTRICTED, STANDARD, ELEVATED

    @ElementCollection
    private Set<String> allowedTools;     // explicit allowlist

    @ElementCollection
    private Set<String> deniedTools;      // explicit denylist (takes precedence)

    @ElementCollection
    private Set<String> egressAllowlist;  // allowed outbound domains for http_fetch

    private int maxTokensPerRequest;
    private int maxToolCallsPerRequest;
    private int maxSessionHistoryTokens;

    @Embedded
    private ContentFilterPolicy contentFilterPolicy;
}
```

#### 5.4 Content Filtering (Prompt Injection Defense)

jclaw implements a multi-layered content filtering chain that OpenClaw explicitly lacks:

```java
public class ContentFilterChain {
    private final List<ContentFilter> filters;

    // Filters execute in order; any can reject or transform content
    // 1. InputSanitizer      — strip control characters, normalize unicode
    // 2. PatternDetector     — regex-based known injection patterns
    // 3. LengthEnforcer      — reject abnormally long inputs
    // 4. InstructionDetector — heuristic detection of "ignore previous instructions"
    // 5. EgressGuard         — on tool outputs, detect data exfiltration attempts
}
```

This is defense-in-depth: the filters don't claim to solve prompt injection, but they raise the cost of exploitation and generate audit events when suspicious patterns are detected.

#### 5.5 Audit Logging

Every security-relevant event is written to a structured audit log in PostgreSQL.

```java
@Entity
@Table(name = "audit_log")
public class AuditEvent {
    @Id private UUID id;
    private Instant timestamp;
    private String eventType;           // AUTH_SUCCESS, AUTH_FAILURE, TOOL_CALL,
                                        // SESSION_CREATE, CONFIG_CHANGE, etc.
    private String principal;           // who
    private String agentId;             // which agent
    private String sessionId;           // which session
    private String channelType;
    private String action;              // what happened
    private String resourceType;
    private String resourceId;

    @Column(columnDefinition = "jsonb")
    private String details;             // structured details (redacted)

    private String sourceIp;
    private String outcome;             // SUCCESS, FAILURE, DENIED, FILTERED
}
```

Audit events are emitted for: authentication attempts, authorization decisions, tool invocations, session lifecycle events, configuration changes, content filter triggers, and admin actions.

#### 5.6 Secrets Management

jclaw never stores secrets in configuration files or environment variables. All secrets are managed through CF service bindings.

| Secret Type | Storage | Access Method |
|---|---|---|
| LLM API credentials | GenAI service broker | VCAP_SERVICES binding (managed by broker) |
| Channel tokens (Slack, Discord, etc.) | CredHub service instance | VCAP_SERVICES + Spring Cloud CredHub |
| Database credentials | CF-managed PostgreSQL | VCAP_SERVICES auto-configuration |
| Redis credentials | CF-managed Redis | VCAP_SERVICES auto-configuration |
| Webhook signing secrets | CredHub service instance | VCAP_SERVICES + Spring Cloud CredHub |

```java
@Configuration
public class SecretsConfig {

    // Channel tokens resolved from CredHub-backed user-provided service
    @Value("${vcap.services.jclaw-secrets.credentials.slack-bot-token}")
    private String slackBotToken;

    @Value("${vcap.services.jclaw-secrets.credentials.discord-bot-token}")
    private String discordBotToken;

    @Value("${vcap.services.jclaw-secrets.credentials.teams-app-password}")
    private String teamsAppPassword;
}
```

#### 5.7 Rate Limiting

Per-user and per-channel rate limits enforced via Redis.

```java
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ReactiveRedisTemplate<String, String> redis;

    // Configurable limits per role:
    // USER:    20 requests/minute, 200 requests/hour
    // SERVICE: 60 requests/minute, 1000 requests/hour
    // ADMIN:   unlimited
}
```

#### 5.8 Data Residency & Retention

```yaml
jclaw:
  security:
    data-retention:
      session-transcripts-days: 90      # auto-purge after 90 days
      audit-log-days: 365               # compliance: 1 year minimum
      content-filter-events-days: 30
    data-residency:
      region: ${CF_REGION:us}           # enforced at DB service level
    pii:
      redact-in-logs: true
      redact-patterns:
        - "\\b\\d{3}-\\d{2}-\\d{4}\\b"   # SSN
        - "\\b\\d{16}\\b"                 # credit card
        - "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}" # email (in tool output)
```

---

### 6. Cloud Foundry Service Bindings

#### 6.1 Required Marketplace Services

| Service | Plan | Purpose | VCAP Key |
|---|---|---|---|
| **GenAI** | `standard` | LLM access (chat completions, embeddings) | `genai` |
| **Single Sign-On** (`p-identity`) | operator-defined plan | OAuth2/OIDC authentication, JWT issuance, IdP federation | `p-identity` |
| **PostgreSQL** | `on-demand-large` | Sessions, audit log, config, identity mappings | `postgres` |
| **Redis** | `on-demand-cache` | Session cache, rate limiting, pub/sub between app instances | `redis` |

#### 6.2 Optional Marketplace Services

| Service | Purpose | Fallback |
|---|---|---|
| **CredHub** (or user-provided service) | Channel tokens, webhook secrets | Environment variables (not recommended) |
| **RabbitMQ** | Cross-instance event bus (alternative to Redis pub/sub) | Redis pub/sub |
| **App Autoscaler** | Scale based on message queue depth | Manual scaling |
| **Spring Cloud Gateway** | SSO-integrated API gateway with route-level auth | Direct app routes |

#### 6.3 Manifest

```yaml
---
applications:
- name: jclaw
  memory: 1G
  instances: 2
  buildpacks:
    - java_buildpack_offline
  path: build/libs/jclaw-1.0.0.jar
  env:
    JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
    SPRING_PROFILES_ACTIVE: cloud
    JCLAW_ADMIN_ALLOWED_ORIGINS: https://jclaw.apps.example.com
  services:
    - jclaw-genai
    - jclaw-sso          # p-identity SSO tile instance
    - jclaw-db
    - jclaw-cache
    - jclaw-secrets
  routes:
    - route: jclaw.apps.example.com
    - route: jclaw-webhooks.apps.example.com
  health-check-type: http
  health-check-http-endpoint: /actuator/health
  timeout: 120
```

#### 6.4 GenAI Service Integration via Spring AI

The GenAI service broker provides LLM access via a standard API. jclaw uses Spring AI's `ChatModel` abstraction, configured to consume the GenAI service binding.

```java
@Configuration
@Profile("cloud")
public class GenAiConfig {

    @Bean
    public ChatModel chatModel(
            @Value("${vcap.services.jclaw-genai.credentials.api-base}") String apiBase,
            @Value("${vcap.services.jclaw-genai.credentials.api-key}") String apiKey,
            @Value("${vcap.services.jclaw-genai.credentials.model:claude-sonnet-4-20250514}") String model) {

        return AnthropicChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(apiBase)
            .defaultOptions(AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(4096)
                .temperature(0.7)
                .build())
            .build();
    }

    // Model routing: different agents can use different models
    @Bean
    public ModelRouter modelRouter(ChatModel defaultModel) {
        return new ModelRouter(defaultModel);
    }
}
```

---

### 7. Session Management

#### 7.1 Session Model

Sessions are the fundamental unit of conversation state. jclaw's session model maps directly from OpenClaw's concept but externalizes all state to PostgreSQL + Redis.

```java
@Entity
@Table(name = "sessions")
public class Session {
    @Id private UUID id;
    private String agentId;
    private String channelType;
    private String channelConversationId;
    private String principal;             // jclaw identity

    @Enumerated(EnumType.STRING)
    private SessionScope scope;           // MAIN, DM, GROUP, API

    @Column(columnDefinition = "jsonb")
    private String metadata;              // channel-specific metadata

    private Instant createdAt;
    private Instant lastActiveAt;
    private int messageCount;
    private int totalTokensUsed;

    @Enumerated(EnumType.STRING)
    private SessionStatus status;         // ACTIVE, COMPACTED, ARCHIVED, PURGED
}
```

#### 7.2 Session Isolation

| Scope | Isolation | Use Case |
|---|---|---|
| `MAIN` | Per-user, cross-channel | Single user's primary assistant session |
| `DM` | Per-user-per-channel | When channel-specific context isolation is needed |
| `GROUP` | Per-channel-conversation | Slack channel, Teams group, etc. |
| `API` | Per-API-client | Service-to-service interactions |

Configuration:

```yaml
jclaw:
  session:
    default-scope: MAIN               # like OpenClaw's dmScope: "main"
    group-scope: GROUP                 # groups always get their own session
    max-history-tokens: 128000         # context window management
    compaction-threshold-tokens: 96000 # trigger compaction at 75%
    idle-timeout-minutes: 1440         # archive after 24h idle
```

#### 7.3 Session History & Compaction

Session history is stored as structured events in PostgreSQL, with the recent window cached in Redis for fast access. When context exceeds the compaction threshold, jclaw generates a summary using the LLM and replaces older messages with the compacted summary.

---

### 8. Multi-Agent Routing

jclaw supports multiple agent personas, each with its own system prompt, tool policy, model configuration, and channel routing. This maps to OpenClaw's multi-agent routing but with enterprise-grade configuration.

```yaml
jclaw:
  agents:
    - id: general
      display-name: "General Assistant"
      model: claude-sonnet-4-20250514
      trust-level: STANDARD
      system-prompt-ref: classpath:prompts/general.md
      allowed-tools: [web_search, http_fetch, channel_send]
      channels:
        - type: slack
          workspace: T12345
          channels: ["C_GENERAL", "C_HELP"]
          activation: MENTION            # only respond when @mentioned

    - id: ops
      display-name: "Ops Assistant"
      model: claude-sonnet-4-20250514
      trust-level: ELEVATED
      system-prompt-ref: classpath:prompts/ops.md
      allowed-tools: [web_search, http_fetch, data_query, scheduled_task]
      egress-allowlist: ["*.internal.example.com", "api.pagerduty.com"]
      channels:
        - type: slack
          workspace: T12345
          channels: ["C_OPS", "C_INCIDENTS"]
          activation: ALWAYS

    - id: api-agent
      display-name: "API Agent"
      model: claude-sonnet-4-20250514
      trust-level: RESTRICTED
      system-prompt-ref: classpath:prompts/api.md
      allowed-tools: [web_search]
      channels:
        - type: rest-api
```

---

### 9. Observability

#### 9.1 Health & Metrics

jclaw exposes Spring Actuator endpoints with custom health indicators:

```java
@Component
public class GenAiHealthIndicator implements HealthIndicator {
    // Checks GenAI service connectivity and model availability
}

@Component
public class ChannelHealthIndicator implements HealthIndicator {
    // Checks each channel adapter's connection status
}
```

Metrics exported via Micrometer (compatible with Prometheus, Datadog, etc.):

- `jclaw.messages.received` — counter by channel, agent
- `jclaw.messages.processed` — counter by channel, agent, outcome (success/filtered/error)
- `jclaw.llm.requests` — counter by model, agent
- `jclaw.llm.tokens.input` — counter by model, agent
- `jclaw.llm.tokens.output` — counter by model, agent
- `jclaw.llm.latency` — histogram by model, agent
- `jclaw.tools.invocations` — counter by tool, agent, outcome
- `jclaw.sessions.active` — gauge
- `jclaw.content_filter.triggered` — counter by filter, action

#### 9.2 Structured Logging

```java
@Configuration
public class LoggingConfig {
    // JSON-structured logging (Logback + logstash-encoder)
    // PII redaction via custom PatternLayout
    // Correlation IDs: request-id, session-id, agent-id, channel-trace-id
}
```

#### 9.3 Distributed Tracing

Spring Micrometer Tracing with propagation through channel adapters and LLM calls for end-to-end request tracing across multiple jclaw instances.

---

### 10. Project Structure

```
jclaw/
├── src/main/java/com/jclaw/
│   ├── JclawApplication.java
│   ├── agent/
│   │   ├── AgentRuntime.java
│   │   ├── AgentContext.java
│   │   ├── AgentConfig.java
│   │   ├── ModelRouter.java
│   │   └── PromptTemplateService.java
│   ├── channel/
│   │   ├── ChannelAdapter.java               # interface
│   │   ├── ChannelRouter.java                # routes messages to agents
│   │   ├── InboundMessage.java
│   │   ├── OutboundMessage.java
│   │   ├── slack/
│   │   │   └── SlackChannelAdapter.java
│   │   ├── teams/
│   │   │   └── TeamsChannelAdapter.java
│   │   ├── discord/
│   │   │   └── DiscordChannelAdapter.java
│   │   ├── googlechat/
│   │   │   └── GoogleChatChannelAdapter.java
│   │   ├── webchat/
│   │   │   └── WebChatChannelAdapter.java
│   │   └── restapi/
│   │       └── RestApiChannelAdapter.java
│   ├── security/
│   │   ├── SsoSecurityConfig.java            # (same as config/ — primary security config)
│   │   ├── ChannelWebhookAuthFilter.java
│   │   ├── AuditLogFilter.java
│   │   ├── RateLimitFilter.java
│   │   ├── RbacService.java
│   │   ├── JclawJwtConverter.java
│   │   └── IdentityMappingService.java
│   ├── content/
│   │   ├── ContentFilterChain.java
│   │   ├── InputSanitizer.java
│   │   ├── PatternDetector.java
│   │   ├── InstructionDetector.java
│   │   └── EgressGuard.java
│   ├── session/
│   │   ├── Session.java
│   │   ├── SessionManager.java
│   │   ├── SessionRepository.java
│   │   ├── CompactionService.java
│   │   └── SessionPurgeScheduler.java
│   ├── tool/
│   │   ├── ToolRegistry.java
│   │   ├── JclawTool.java                    # annotation
│   │   ├── ToolPolicy.java
│   │   ├── builtin/
│   │   │   ├── WebSearchTool.java
│   │   │   ├── HttpFetchTool.java
│   │   │   ├── DataQueryTool.java
│   │   │   ├── ScheduledTaskTool.java
│   │   │   ├── ChannelSendTool.java
│   │   │   ├── SessionListTool.java
│   │   │   ├── SessionHistoryTool.java
│   │   │   └── SessionSendTool.java
│   │   └── validation/
│   │       └── EgressAllowlistValidator.java
│   ├── audit/
│   │   ├── AuditEvent.java
│   │   ├── AuditService.java
│   │   └── AuditRepository.java
│   └── config/
│       ├── GenAiConfig.java
│       ├── SsoSecurityConfig.java            # SSO tile + JWT + OAuth2 login
│       ├── RedisConfig.java
│       ├── SecretsConfig.java
│       └── JclawProperties.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-cloud.yml                  # CF profile (java-cfenv auto-config)
│   ├── application-local.yml                  # local dev (explicit OAuth2 config)
│   ├── prompts/
│   │   ├── general.md
│   │   ├── ops.md
│   │   └── api.md
│   └── db/migration/                         # Flyway migrations
│       ├── V1__initial_schema.sql
│       ├── V2__audit_log.sql
│       └── V3__identity_mappings.sql
├── src/test/java/com/jclaw/
│   ├── agent/AgentRuntimeTest.java
│   ├── channel/SlackChannelAdapterTest.java
│   ├── security/SecurityConfigTest.java
│   ├── content/ContentFilterChainTest.java
│   └── integration/
│       └── FullFlowIntegrationTest.java
├── manifest.yml
├── build.gradle                              # or pom.xml
└── README.md
```

---

### 11. Dependencies (Gradle)

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.4.2'
    id 'io.spring.dependency-management' version '1.1.7'
}

java { sourceCompatibility = '21' }

dependencies {
    // Spring Boot core
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-websocket'
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // Spring AI
    implementation 'org.springframework.ai:spring-ai-anthropic-spring-boot-starter:1.0.0'
    implementation 'org.springframework.ai:spring-ai-openai-spring-boot-starter:1.0.0'

    // Cloud Foundry + SSO tile integration
    implementation 'io.pivotal.cfenv:java-cfenv-boot:3.2.0'
    implementation 'io.pivotal.cfenv:java-cfenv-boot-pivotal-sso:3.2.0'
    implementation 'org.springframework.cloud:spring-cloud-spring-service-connector'
    implementation 'org.springframework.cloud:spring-cloud-cloudfoundry-connector'

    // Database
    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'

    // Channel SDKs
    implementation 'com.slack.api:bolt-socket-mode:1.44.2'
    implementation 'com.slack.api:slack-api-client:1.44.2'
    implementation 'net.dv8tion:JDA:5.2.2'
    implementation 'com.microsoft.bot:bot-builder:4.16.0'
    implementation 'com.google.apis:google-api-services-chat:v1-rev20241204-2.0.0'

    // Observability
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'net.logstash.logback:logstash-logback-encoder:8.0'

    // Utilities
    implementation 'com.fasterxml.jackson.module:jackson-module-kotlin'
    implementation 'com.bucket4j:bucket4j-redis:8.14.0'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'io.projectreactor:reactor-test'
}
```

---

### 12. Database Schema (Flyway V1)

```sql
-- V1__initial_schema.sql

CREATE TABLE sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id        VARCHAR(64) NOT NULL,
    channel_type    VARCHAR(32) NOT NULL,
    channel_conversation_id VARCHAR(256),
    principal       VARCHAR(256) NOT NULL,
    scope           VARCHAR(16) NOT NULL DEFAULT 'MAIN',
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    message_count   INT NOT NULL DEFAULT 0,
    total_tokens    INT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);

CREATE INDEX idx_sessions_principal ON sessions(principal, status);
CREATE INDEX idx_sessions_agent ON sessions(agent_id, status);
CREATE INDEX idx_sessions_channel ON sessions(channel_type, channel_conversation_id);

CREATE TABLE session_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,    -- USER, ASSISTANT, SYSTEM, TOOL
    content         TEXT NOT NULL,
    token_count     INT,
    tool_calls      JSONB,
    tool_results    JSONB,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_compacted    BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_messages_session ON session_messages(session_id, created_at);

CREATE TABLE agent_configs (
    agent_id        VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(256),
    model           VARCHAR(128),
    trust_level     VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    system_prompt   TEXT,
    allowed_tools   TEXT[],
    denied_tools    TEXT[],
    egress_allowlist TEXT[],
    max_tokens_per_request INT DEFAULT 4096,
    max_tool_calls  INT DEFAULT 10,
    max_history_tokens INT DEFAULT 128000,
    config_json     JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_mappings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_type    VARCHAR(32) NOT NULL,
    channel_user_id VARCHAR(256) NOT NULL,
    jclaw_principal VARCHAR(256) NOT NULL,
    display_name    VARCHAR(256),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ,
    approved        BOOLEAN NOT NULL DEFAULT false,
    approved_by     VARCHAR(256),
    UNIQUE(channel_type, channel_user_id)
);

CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_type      VARCHAR(64) NOT NULL,
    principal       VARCHAR(256),
    agent_id        VARCHAR(64),
    session_id      UUID,
    channel_type    VARCHAR(32),
    action          VARCHAR(256) NOT NULL,
    resource_type   VARCHAR(64),
    resource_id     VARCHAR(256),
    details         JSONB DEFAULT '{}',
    source_ip       VARCHAR(45),
    outcome         VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
);

CREATE INDEX idx_audit_timestamp ON audit_log(timestamp DESC);
CREATE INDEX idx_audit_principal ON audit_log(principal, timestamp DESC);
CREATE INDEX idx_audit_event_type ON audit_log(event_type, timestamp DESC);

-- Partitioning for audit_log (by month) recommended for production
```

---

### 13. Deployment & Operations

#### 13.1 Deployment Workflow

```bash
# 1. Create marketplace services
cf create-service genai standard jclaw-genai
cf create-service p-identity jclaw-sso-plan jclaw-sso    # SSO tile
cf create-service postgres on-demand-large jclaw-db
cf create-service redis on-demand-cache jclaw-cache

# 2. Configure the SSO app in the SSO Developer Dashboard:
#    - App Type: Web App (authorization code) + Service-to-Service (client credentials)
#    - Scopes: openid, profile, email, roles
#    - Resources: jclaw.admin, jclaw.operator, jclaw.user, jclaw.service
#    - Redirect URI: https://jclaw.apps.example.com/login/oauth2/code/sso
#    - Identity Providers: select the configured IdP(s) from the plan

# 3. Create CredHub-backed user-provided service for channel secrets
cf create-user-provided-service jclaw-secrets -p '{
  "slack-bot-token": "xoxb-...",
  "slack-app-token": "xapp-...",
  "discord-bot-token": "...",
  "teams-app-password": "...",
  "google-chat-credentials": "{...}"
}'

# 4. Build and push
./gradlew bootJar
cf push

# 5. Verify
cf app jclaw
curl https://jclaw.apps.example.com/actuator/health

# 6. Test SSO login
open https://jclaw.apps.example.com/admin/dashboard
# → redirects to SSO login page → IdP login → callback → dashboard
```

#### 13.2 Scaling

jclaw is stateless by design. Scale horizontally with `cf scale jclaw -i N`. Redis pub/sub ensures channel events and session updates propagate across instances. Sticky sessions are not required.

#### 13.3 Blue-Green Deployment

```bash
cf push jclaw-green -f manifest.yml --no-route
cf map-route jclaw-green apps.example.com --hostname jclaw
cf unmap-route jclaw-blue apps.example.com --hostname jclaw
cf delete jclaw-blue -f
cf rename jclaw-green jclaw
```

---

### 14. Roadmap

| Phase | Scope | Target |
|---|---|---|
| **v0.1 — Foundation** | Spring Boot app, GenAI binding, SSO tile binding (`p-identity` + `java-cfenv`), REST API channel, session management, audit log. Single agent. | 4 weeks |
| **v0.2 — Channels** | Slack + Teams adapters, identity mapping (channel→SSO principal), content filter chain. | 3 weeks |
| **v0.3 — Tools** | Tool registry, web_search, http_fetch, egress allowlist, per-agent tool policies. | 3 weeks |
| **v0.4 — Multi-Agent** | Multiple agents, channel routing, cross-session messaging, per-scope RBAC enforcement. | 2 weeks |
| **v0.5 — Hardening** | Rate limiting (per-scope via Redis), session compaction, data retention purge, observability dashboards. | 2 weeks |
| **v1.0 — GA** | Discord + Google Chat adapters, WebChat UI (SSO login flow), comprehensive test suite, documentation. | 3 weeks |

---

### 15. Security Checklist (Pre-Production)

- [ ] All LLM credentials sourced from GenAI service binding (no env vars)
- [ ] All channel tokens in CredHub-backed service (no config files)
- [ ] SSO tile (`p-identity`) service instance created and bound
- [ ] SSO app configured with correct redirect URIs and scopes
- [ ] External IdP linked in SSO plan (Okta/Azure AD/ADFS/etc.)
- [ ] Custom scopes (`jclaw.admin`, `jclaw.operator`, `jclaw.user`) defined as resources
- [ ] IdP group-to-scope mappings configured for RBAC
- [ ] Token validation confirmed (signature, audience, issuer, expiry)
- [ ] `java-cfenv-boot-pivotal-sso` auto-configuring from VCAP_SERVICES
- [ ] Identity mappings require admin approval for channel users
- [ ] Content filter chain enabled with org-specific patterns
- [ ] Per-agent tool policies reviewed and locked down
- [ ] Egress allowlists configured for http_fetch tool
- [ ] Audit logging verified and retention policy set
- [ ] PII redaction patterns validated
- [ ] Rate limits configured per SSO scope/role
- [ ] Health check endpoints accessible to CF (unauthenticated)
- [ ] Structured logging with correlation IDs confirmed
- [ ] Flyway migrations tested against production-equivalent DB
- [ ] Penetration test against channel webhook endpoints
- [ ] Load test at expected message volume (N messages/minute)
- [ ] Service-to-service (client credentials) flow tested for API clients

---

*End of Specification*
