package com.jclaw.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecretsConfig {

    @Value("${vcap.services.jclaw-secrets.credentials.slack-bot-token:}")
    private String slackBotToken;

    @Value("${vcap.services.jclaw-secrets.credentials.slack-app-token:}")
    private String slackAppToken;

    @Value("${vcap.services.jclaw-secrets.credentials.discord-bot-token:}")
    private String discordBotToken;

    @Value("${vcap.services.jclaw-secrets.credentials.teams-app-password:}")
    private String teamsAppPassword;

    @Value("${vcap.services.jclaw-secrets.credentials.google-chat-credentials:}")
    private String googleChatCredentials;

    @Value("${vcap.services.jclaw-secrets.credentials.slack-signing-secret:}")
    private String slackSigningSecret;

    @Value("${vcap.services.jclaw-secrets.credentials.discord-public-key:}")
    private String discordPublicKey;

    public String getSlackBotToken() { return slackBotToken; }
    public String getSlackAppToken() { return slackAppToken; }
    public String getDiscordBotToken() { return discordBotToken; }
    public String getTeamsAppPassword() { return teamsAppPassword; }
    public String getGoogleChatCredentials() { return googleChatCredentials; }
    public String getSlackSigningSecret() { return slackSigningSecret; }
    public String getDiscordPublicKey() { return discordPublicKey; }
}
