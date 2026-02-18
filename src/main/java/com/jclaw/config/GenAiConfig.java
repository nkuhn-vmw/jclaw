package com.jclaw.config;

import com.jclaw.agent.ModelRouter;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class GenAiConfig {

    @Bean
    @Profile("cloud")
    public ChatModel cloudChatModel(
            @Value("${vcap.services.jclaw-genai.credentials.api-base}") String apiBase,
            @Value("${vcap.services.jclaw-genai.credentials.api-key}") String apiKey,
            @Value("${vcap.services.jclaw-genai.credentials.model:claude-sonnet-4-20250514}") String model) {
        return createAnthropicModel(apiBase, apiKey, model);
    }

    @Bean
    @Profile("local")
    @ConditionalOnProperty(name = "jclaw.genai.api-key")
    public ChatModel localChatModel(JclawProperties properties) {
        JclawProperties.GenAiProperties genai = properties.getGenai();
        return createAnthropicModel(genai.getApiBase(), genai.getApiKey(), genai.getModel());
    }

    @Bean
    @Profile("test")
    @org.springframework.context.annotation.Primary
    public ChatModel testChatModel() {
        // Return a mock-friendly model for testing
        return createAnthropicModel("http://localhost:8089", "test-key", "claude-sonnet-4-20250514");
    }

    @Bean
    public ModelRouter modelRouter(ChatModel defaultModel, ApplicationContext applicationContext) {
        return new ModelRouter(defaultModel, applicationContext);
    }

    private ChatModel createAnthropicModel(String apiBase, String apiKey, String model) {
        AnthropicApi api = apiBase != null && !apiBase.isEmpty()
                ? new AnthropicApi(apiBase, apiKey)
                : new AnthropicApi(apiKey);

        return new AnthropicChatModel(api, AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(4096)
                .temperature(0.7)
                .build());
    }
}
