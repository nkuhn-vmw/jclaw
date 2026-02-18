package com.jclaw.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecretsConfig {

    @Value("${vcap.services.jclaw-secrets.credentials.slack-bot-token:#{null}}")
    private String slackBotToken;

    @Value("${vcap.services.jclaw-secrets.credentials.slack-app-token:#{null}}")
    private String slackAppToken;

    @Value("${vcap.services.jclaw-secrets.credentials.discord-bot-token:#{null}}")
    private String discordBotToken;

    @Value("${vcap.services.jclaw-secrets.credentials.teams-app-password:#{null}}")
    private String teamsAppPassword;

    @Value("${vcap.services.jclaw-secrets.credentials.google-chat-credentials:#{null}}")
    private String googleChatCredentials;

    @Value("${vcap.services.jclaw-secrets.credentials.slack-signing-secret:#{null}}")
    private String slackSigningSecret;

    @Value("${vcap.services.jclaw-secrets.credentials.discord-public-key:#{null}}")
    private String discordPublicKey;

    @Value("${vcap.services.jclaw-secrets.credentials.search-api-key:#{null}}")
    private String searchApiKey;

    @Value("${jclaw.teams.app-id:#{null}}")
    private String teamsAppId;

    @Value("${jclaw.google-chat.project-number:#{null}}")
    private String googleChatProjectNumber;

    public String getSlackBotToken() { return slackBotToken; }
    public String getSlackAppToken() { return slackAppToken; }
    public String getDiscordBotToken() { return discordBotToken; }
    public String getTeamsAppPassword() { return teamsAppPassword; }
    public String getGoogleChatCredentials() { return googleChatCredentials; }
    public String getSlackSigningSecret() { return slackSigningSecret; }
    public String getDiscordPublicKey() { return discordPublicKey; }
    public String getSearchApiKey() { return searchApiKey; }
    public String getTeamsAppId() { return teamsAppId; }
    public String getGoogleChatProjectNumber() { return googleChatProjectNumber; }
}
