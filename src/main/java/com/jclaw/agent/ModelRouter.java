package com.jclaw.agent;

import com.jclaw.config.GenAiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.ApplicationContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes agents to their configured ChatModel.
 * Declared as a @Bean in GenAiConfig per spec §6.4.
 *
 * Discovers models from:
 * 1. Spring ApplicationContext (ChatModel beans)
 * 2. GenAiConfig cloud models (Tanzu GenAI multi-model discovery)
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

        // Register cloud models from GenAiConfig (Tanzu GenAI multi-model support)
        try {
            GenAiConfig genAiConfig = applicationContext.getBean(GenAiConfig.class);
            Map<String, ChatModel> cloudModels = genAiConfig.getCloudModels();
            for (Map.Entry<String, ChatModel> entry : cloudModels.entrySet()) {
                if (!modelRegistry.containsKey(entry.getKey())) {
                    modelRegistry.put(entry.getKey(), entry.getValue());
                    log.info("Registered cloud model: {}", entry.getKey());
                }
            }
        } catch (Exception e) {
            log.debug("GenAiConfig not available for cloud model registration");
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
                log.debug("Could not extract model ID from AnthropicChatModel", e);
            }
        }
        if (chatModel instanceof OpenAiChatModel openAiModel) {
            try {
                var options = openAiModel.getDefaultOptions();
                if (options != null && options.getModel() != null) {
                    return options.getModel();
                }
            } catch (Exception e) {
                log.debug("Could not extract model ID from OpenAiChatModel", e);
            }
        }
        return null;
    }

    void registerModel(String modelId, ChatModel model) {
        modelRegistry.put(modelId, model);
    }

    public List<String> getAvailableModelNames() {
        return new ArrayList<>(modelRegistry.keySet());
    }

    public ChatModel getDefaultModel() {
        return defaultModel;
    }
}
