package com.jclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes agents to their configured ChatModel.
 * Declared as a @Bean in GenAiConfig per spec §6.4.
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ChatModel defaultModel;
    private final Map<String, ChatModel> modelRegistry = new ConcurrentHashMap<>();
    private final ApplicationContext applicationContext;

    public ModelRouter(ChatModel defaultModel, ApplicationContext applicationContext) {
        this.defaultModel = defaultModel;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        // Auto-discover all ChatModel beans and register them by both bean name and model ID
        Map<String, ChatModel> models = applicationContext.getBeansOfType(ChatModel.class);
        for (Map.Entry<String, ChatModel> entry : models.entrySet()) {
            String beanName = entry.getKey();
            ChatModel chatModel = entry.getValue();
            modelRegistry.put(beanName, chatModel);

            // Also register by model ID (e.g., "claude-sonnet-4-20250514") for config lookup
            String modelId = extractModelId(chatModel);
            if (modelId != null && !modelId.equals(beanName)) {
                modelRegistry.put(modelId, chatModel);
                log.info("Registered model: {} (alias: {})", beanName, modelId);
            } else {
                log.info("Registered model: {}", beanName);
            }
        }
        log.info("Model router initialized with {} entries (default={})",
                modelRegistry.size(), defaultModel.getClass().getSimpleName());
    }

    public ChatModel resolveModel(String agentId, AgentConfig config) {
        if (config == null || config.getModel() == null) {
            return defaultModel;
        }
        return modelRegistry.getOrDefault(config.getModel(), defaultModel);
    }

    private String extractModelId(ChatModel chatModel) {
        if (chatModel instanceof AnthropicChatModel anthropicModel) {
            try {
                var options = anthropicModel.getDefaultOptions();
                if (options != null && options.getModel() != null) {
                    return options.getModel();
                }
            } catch (Exception e) {
                log.debug("Could not extract model ID from ChatModel", e);
            }
        }
        return null;
    }

    public void registerModel(String modelId, ChatModel model) {
        modelRegistry.put(modelId, model);
    }

    public ChatModel getDefaultModel() {
        return defaultModel;
    }
}
