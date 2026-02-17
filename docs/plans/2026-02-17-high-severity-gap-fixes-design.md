# High Severity Gap Fixes Design

## Problem
Gap analysis identified 27 HIGH severity gaps across 4 layers where the implementation deviates from the tech spec. These gaps include broken multi-turn memory, missing metrics, unenforced safety limits, broken channel auth, and missing JSONB column definitions.

## Approach
Theme-based batches, each batch a single commit. 8 batches total.

## Batches

### Batch 1: JSONB Column Definitions
**Gaps:** GAP-BT02, BT03, BT04, BT08
**Files:** AuditEvent.java, Session.java, SessionMessage.java, AgentConfig.java
**Change:** Add `columnDefinition = "jsonb"` to 5 `@Column` annotations. No logic changes.

### Batch 2: Metrics Overhaul
**Gaps:** GAP-OCD-01, OCD-03, OCD-04, OCD-06, OCD-30, ART-020
**Files:** JclawMetrics.java, AgentRuntime.java, ToolRegistry.java
**Changes:**
- Rewrite JclawMetrics with spec-correct names: `jclaw.messages.received`, `jclaw.llm.requests`, `jclaw.llm.tokens.input`, `jclaw.llm.tokens.output`, `jclaw.tools.invocations`, `jclaw.sessions.active` (gauge), `jclaw.content_filter.triggered`
- All counters tagged per spec (channel/agent, model/agent, tool/agent/outcome, filter/action)
- Extract token usage from ChatResponse metadata in AgentRuntime
- Record jclaw.llm.requests counter in AgentRuntime

### Batch 3: Agent Runtime Core
**Gaps:** GAP-ART-001, ART-002, ART-003
**Files:** AgentRuntime.java
**Changes:**
- Inject ChatClient.Builder instead of constructing ChatClient from raw ChatModel per-request
- Store assistant response in session via sessionManager.addMessage(MessageRole.ASSISTANT, ...)
- Read config.getMaxTokensPerRequest() and set on ChatClient call options
- Count tool calls and enforce config.getMaxToolCalls() limit

### Batch 4: Observability Infrastructure
**Gaps:** GAP-OCD-09, OCD-10, OCD-23
**Files:** CorrelationIdFilter.java, build.gradle, SecretsConfig.java
**Changes:**
- Expand CorrelationIdFilter to propagate session-id, agent-id, channel-type, channel-trace-id via MDC
- Add micrometer-tracing-bridge-otel dependency to build.gradle
- Remove empty-string defaults from SecretsConfig @Value annotations (fail-fast without VCAP_SERVICES in cloud profile)

### Batch 5: Tool Audit & Stubs
**Gaps:** GAP-BT05, BT01
**Files:** ToolRegistry.java, WebSearchTool.java, AgentRuntime.java
**Changes:**
- Pass AgentContext through to AuditedToolCallback so tool audit events include principal, agentId, sessionId
- Replace WebSearch hardcoded placeholder with configurable search provider (SerpAPI/Brave) using SecretsConfig.getSearchApiKey()

### Batch 6: Channel Adapters
**Gaps:** GAP-CA-001, CA-002, CA-004, CA-006, CA-009, CA-017
**Files:** TeamsChannelAdapter.java, GoogleChatChannelAdapter.java, SlackChannelAdapter.java, ChannelRouter.java, OutboundMessage.java, IdentityMappingService.java
**Changes:**
- Teams: Implement proper OAuth token exchange (appId + password -> JWT) instead of raw password as bearer
- Google Chat: Inject SecretsConfig, use service account credentials for outbound API auth
- Slack: Add "team" metadata to AppMention events, add "mentioned" metadata to regular messages containing @mentions
- ChannelRouter: Propagate threadId from inbound to outbound message
- ChannelRouter: Queue unmapped identity messages instead of dropping (store in DB for later processing)
- ChannelRouter: Bypass identity mapping for WebChat users who are already SSO-authenticated

### Batch 7: Session & Data Retention
**Gaps:** GAP-ART-006, BT24
**Files:** SessionManager.java, RedisConfig.java, SessionPurgeScheduler.java
**Changes:**
- Add Redis cache layer for recent session history (read-through cache with TTL)
- Add content filter event purge to SessionPurgeScheduler

### Batch 8: Statelessness
**Gaps:** GAP-BT10
**Files:** ScheduledTaskTool.java, V5__scheduled_tasks.sql (new migration)
**Changes:**
- Replace in-memory ConcurrentHashMap with database-backed scheduled task persistence
- Add Flyway migration for scheduled_tasks table
- Use Spring @Scheduled to poll for due tasks from DB
