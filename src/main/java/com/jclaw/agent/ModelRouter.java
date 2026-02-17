package com.jclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
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
        // Auto-discover all ChatModel beans and register them by their bean name
        Map<String, ChatModel> models = applicationContext.getBeansOfType(ChatModel.class);
        for (Map.Entry<String, ChatModel> entry : models.entrySet()) {
            String beanName = entry.getKey();
            modelRegistry.put(beanName, entry.getValue());
            log.info("Registered model: {}", beanName);
        }
        log.info("Model router initialized with {} models (default={})",
                modelRegistry.size(), defaultModel.getClass().getSimpleName());
    }

    public ChatModel resolveModel(String agentId, AgentConfig config) {
        if (config == null || config.getModel() == null) {
            return defaultModel;
        }
        return modelRegistry.getOrDefault(config.getModel(), defaultModel);
    }

    public void registerModel(String modelId, ChatModel model) {
        modelRegistry.put(modelId, model);
    }

    public ChatModel getDefaultModel() {
        return defaultModel;
    }
}
