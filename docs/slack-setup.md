# Connecting jclaw to Slack

This guide walks you through setting up jclaw as a Slack bot that responds to messages in channels and DMs.

jclaw uses **Slack Socket Mode** (persistent WebSocket), so you don't need to expose any webhook endpoints or configure request URLs.

## Prerequisites

- Admin access to a Slack workspace
- A running jclaw instance on TAS (or locally)
- `cf` CLI access to update service credentials

## Step 1: Create a Slack App

1. Go to https://api.slack.com/apps
2. Click **Create New App** > **From scratch**
3. Name it (e.g., `jclaw`) and select your workspace
4. Click **Create App**

## Step 2: Enable Socket Mode

1. In the left sidebar, go to **Settings > Socket Mode**
2. Toggle **Enable Socket Mode** to ON
3. When prompted, name the token (e.g., `jclaw-socket`) and click **Generate**
4. Copy the **App-Level Token** (starts with `xapp-`) -- you'll need this later

## Step 3: Add Bot Scopes

1. Go to **OAuth & Permissions** in the sidebar
2. Scroll to **Scopes > Bot Token Scopes** and add:

| Scope | Purpose |
|-------|---------|
| `chat:write` | Send messages |
| `app_mentions:read` | Detect @mentions |
| `channels:history` | Read messages in public channels |
| `groups:history` | Read messages in private channels |
| `im:history` | Read direct messages |
| `im:read` | Know which DMs are open |
| `im:write` | Open DMs with users |
| `users:read` | Look up user display names |

## Step 4: Subscribe to Events

1. Go to **Event Subscriptions** in the sidebar
2. Toggle **Enable Events** to ON
3. Under **Subscribe to bot events**, add:

| Event | Purpose |
|-------|---------|
| `message.channels` | Messages in public channels |
| `message.groups` | Messages in private channels |
| `message.im` | Direct messages |
| `app_mention` | When someone @mentions the bot |

4. Click **Save Changes**

## Step 5: Install the App

1. Go to **OAuth & Permissions**
2. Click **Install to Workspace** (or **Reinstall** if already installed)
3. Authorize the permissions
4. Copy the **Bot User OAuth Token** (starts with `xoxb-`)

## Step 6: Configure jclaw Credentials

Update the `jclaw-secrets` user-provided service with both tokens:

```bash
cf target -o scale-test -s runner2

# Get current credentials first
cf env jclaw | grep -A20 jclaw-secrets

# Update with Slack tokens added
cf update-user-provided-service jclaw-secrets -p '{
  "placeholder": "true",
  "search-api-key": "YOUR_EXISTING_SEARCH_KEY",
  "slack-bot-token": "xoxb-YOUR-BOT-TOKEN",
  "slack-app-token": "xapp-YOUR-APP-TOKEN"
}'
```

Then restart jclaw to pick up the new credentials:

```bash
cf restart jclaw
```

## Step 7: Invite the Bot to a Channel

In Slack, go to the channel where you want the bot and type:

```
/invite @jclaw
```

Or: Channel settings > Integrations > Add an app > select jclaw.

## Step 8: Approve Identity Mappings

The first time a Slack user messages the bot, their Slack user ID needs to be mapped to a jclaw principal. Until an admin approves this, the bot won't respond.

1. Open the dashboard at https://jclaw.apps.tas-ndc.kuhn-labs.com
2. Go to the **Admin** tab
3. Look under **Pending Identity Mappings**
4. For each pending user, enter their jclaw principal (e.g., their username) and click **Approve**

After approval, that Slack user's messages will be processed by the agent.

## How It Works

```
Slack user sends message
        |
        v
Socket Mode WebSocket --> SlackChannelAdapter
        |
        v
ChannelRouter checks activation mode
        |
        v
Identity mapping (Slack user ID --> jclaw principal)
        |
        v
AgentRuntime processes with LLM + tools
        |
        v
Response sent back to Slack channel/thread
```

- **Threading is preserved**: if a user replies in a thread, the bot responds in that thread
- **Long messages are chunked**: responses over 4000 chars are split at natural break points
- **All interactions are audit-logged** under the mapped principal

## Activation Modes

By default, the bot responds to ALL messages in channels it's invited to. You can configure activation modes via `application.yml`:

### Respond only when @mentioned

```yaml
jclaw:
  agents:
    - id: default
      channels:
        - type: slack
          activation: MENTION
```

### Respond only to DMs

```yaml
jclaw:
  agents:
    - id: default
      channels:
        - type: slack
          activation: DM
```

### Respond to everything (default)

```yaml
jclaw:
  agents:
    - id: default
      channels:
        - type: slack
          activation: ALWAYS
```

## Verification

After setup, check the app logs to confirm the Slack adapter started:

```bash
cf logs jclaw --recent | grep -i slack
```

You should see:
```
Slack Socket Mode started
```

You can also check the health endpoint:

```bash
curl https://jclaw.apps.tas-ndc.kuhn-labs.com/actuator/health
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Bot doesn't respond at all | Tokens not configured | Check `cf env jclaw` for slack tokens |
| "Slack bot token not configured" in logs | Missing `slack-bot-token` | Update `jclaw-secrets` service |
| "Socket Mode disabled" in logs | Missing `slack-app-token` | Update `jclaw-secrets` service |
| Bot ignores a user | Identity not mapped | Approve mapping in dashboard Admin tab |
| Bot doesn't respond in a channel | Bot not invited | `/invite @jclaw` in the channel |
| Bot ignores non-mention messages | Activation mode is MENTION | Change to ALWAYS or DM as needed |
