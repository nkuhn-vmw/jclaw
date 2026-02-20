package com.jclaw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jclaw.agent.ModelRouter;
import io.pivotal.cfenv.core.CfCredentials;
import io.pivotal.cfenv.core.CfEnv;
import io.pivotal.cfenv.core.CfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

/**
 * GenAI configuration supporting both Tanzu GenAI service binding formats:
 *
 * 1. Endpoint format (multi-model): credentials.endpoint contains api_base, api_key, config_url.
 *    The GenAI proxy exposes multiple models through a single binding.
 *    Models are discovered dynamically via the OpenAI-compatible /v1/models endpoint.
 *
 * 2. Direct binding format (single-model): flat credentials with api_base, api_key, model_name.
 *    Each service binding maps to exactly one model.
 *
 * Tanzu GenAI always has priority (@Primary) over other model providers in the cloud profile.
 */
@Configuration
public class GenAiConfig {

    private static final Logger log = LoggerFactory.getLogger(GenAiConfig.class);

    private final Map<String, ChatModel> cloudModels = new LinkedHashMap<>();
    private boolean cloudModelsInitialized = false;

    // === Cloud Profile (Tanzu GenAI — @Primary, highest priority) ===

    @Bean("cloudChatModel")
    @Profile("cloud")
    @Primary
    public ChatModel cloudChatModel() {
        ensureCloudModelsInitialized();
        if (cloudModels.isEmpty()) {
            throw new IllegalStateException(
                    "No GenAI chat models found in VCAP_SERVICES — check service bindings");
        }
        String defaultModel = cloudModels.keySet().iterator().next();
        log.info("Primary cloud ChatModel: {} (total available: {})", defaultModel, cloudModels.size());
        return cloudModels.get(defaultModel);
    }

    // === Local Profile (Anthropic direct) ===

    @Bean
    @Profile("local")
    @ConditionalOnProperty(name = "jclaw.genai.api-key")
    public ChatModel localChatModel(JclawProperties properties) {
        JclawProperties.GenAiProperties genai = properties.getGenai();
        return createAnthropicModel(genai.getApiBase(), genai.getApiKey(), genai.getModel());
    }

    // === Test Profile ===

    @Bean
    @Profile("test")
    @Primary
    public ChatModel testChatModel() {
        return createAnthropicModel("http://localhost:8089", "test-key", "claude-sonnet-4-20250514");
    }

    // === Model Router ===

    @Bean
    public ModelRouter modelRouter(ChatModel defaultModel, ApplicationContext applicationContext) {
        return new ModelRouter(defaultModel, applicationContext);
    }

    // === Cloud Model Access ===

    /**
     * Returns all cloud-discovered ChatModels keyed by model name.
     * Used by ModelRouter for agent-specific model routing.
     */
    public Map<String, ChatModel> getCloudModels() {
        return Collections.unmodifiableMap(cloudModels);
    }

    /**
     * Get a cloud ChatModel by name, falling back to the primary model.
     */
    public ChatModel getCloudModelByName(String modelName) {
        ChatModel model = cloudModels.get(modelName);
        if (model != null) return model;
        if (!cloudModels.isEmpty()) {
            String defaultName = cloudModels.keySet().iterator().next();
            log.debug("Model '{}' not found, using default: {}", modelName, defaultName);
            return cloudModels.get(defaultName);
        }
        return null;
    }

    /**
     * Returns the list of available cloud model names.
     */
    public List<String> getAvailableModelNames() {
        return new ArrayList<>(cloudModels.keySet());
    }

    // === VCAP_SERVICES Parsing ===

    private synchronized void ensureCloudModelsInitialized() {
        if (cloudModelsInitialized) return;
        cloudModelsInitialized = true;

        try {
            CfEnv cfEnv = new CfEnv();
            List<CfService> genaiServices = cfEnv.findServicesByLabel("genai");
            log.info("Found {} GenAI service(s) in VCAP_SERVICES", genaiServices.size());

            for (CfService service : genaiServices) {
                try {
                    if (isEndpointFormat(service)) {
                        initializeFromEndpoint(service);
                    } else {
                        initializeFromDirectBinding(service);
                    }
                } catch (Exception e) {
                    log.warn("Failed to configure GenAI service {}: {}",
                            service.getName(), e.getMessage(), e);
                }
            }

            log.info("Registered {} cloud ChatModel(s): {}", cloudModels.size(), cloudModels.keySet());
        } catch (Exception e) {
            log.error("Failed to initialize cloud GenAI models from VCAP_SERVICES: {}", e.getMessage(), e);
        }
    }

    /**
     * Detects endpoint binding format (multi-model).
     * Endpoint format has credentials.endpoint as a Map with api_base, api_key.
     */
    private boolean isEndpointFormat(CfService service) {
        Map<String, Object> creds = service.getCredentials().getMap();
        Object endpoint = creds.get("endpoint");
        return endpoint instanceof Map;
    }

    /**
     * Initialize from endpoint format (multi-model).
     * Discovers models via the OpenAI-compatible /v1/models endpoint.
     */
    @SuppressWarnings("unchecked")
    private void initializeFromEndpoint(CfService service) {
        Map<String, Object> creds = service.getCredentials().getMap();
        Map<String, Object> endpoint = (Map<String, Object>) creds.get("endpoint");

        String apiBase = (String) endpoint.get("api_base");
        String apiKey = (String) endpoint.get("api_key");

        if (apiBase == null || apiKey == null) {
            log.warn("GenAI endpoint service {} missing api_base or api_key", service.getName());
            return;
        }

        // GenAI proxy uses {api_base}/openai as the OpenAI-compatible base URL
        String openAiBaseUrl = apiBase + "/openai";
        log.info("Initializing GenAI endpoint service: {} (base: {})", service.getName(), openAiBaseUrl);

        // Discover available models dynamically
        List<String> modelNames = discoverModels(openAiBaseUrl, apiKey);
        if (modelNames.isEmpty()) {
            log.warn("No models discovered from GenAI endpoint {}, registering default", service.getName());
            registerOpenAiModel(openAiBaseUrl, apiKey, "openai/gpt-oss-120b", service.getName());
            return;
        }

        for (String modelName : modelNames) {
            if (isEmbeddingModel(modelName)) {
                log.info("Skipping embedding model: {}", modelName);
                continue;
            }
            registerOpenAiModel(openAiBaseUrl, apiKey, modelName, service.getName());
        }
    }

    /**
     * Initialize from direct binding format (single-model).
     * Flat credentials with api_base, api_key, model_name.
     */
    private void initializeFromDirectBinding(CfService service) {
        CfCredentials creds = service.getCredentials();

        String apiBase = creds.getString("api_base");
        if (apiBase == null) apiBase = creds.getString("uri");
        if (apiBase == null) apiBase = creds.getString("url");

        String apiKey = creds.getString("api_key");

        String modelName = creds.getString("model_name");
        if (modelName == null) modelName = creds.getString("model");
        if (modelName == null) modelName = service.getName();

        if (apiBase == null || apiKey == null) {
            log.warn("GenAI direct binding {} missing api_base or api_key", service.getName());
            return;
        }

        if (isEmbeddingModel(modelName) || isEmbeddingModel(service.getName())) {
            log.info("Skipping embedding model: {} (service: {})", modelName, service.getName());
            return;
        }

        log.info("Initializing GenAI direct binding: {} (model: {}, base: {})",
                service.getName(), modelName, apiBase);

        registerOpenAiModel(apiBase, apiKey, modelName, service.getName());
    }

    /**
     * Register an OpenAI-compatible ChatModel with extended timeouts for large model inference.
     */
    private void registerOpenAiModel(String baseUrl, String apiKey, String modelName, String serviceName) {
        try {
            var httpClient = reactor.netty.http.client.HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(120));
            var requestFactory = new org.springframework.http.client.ReactorClientHttpRequestFactory(httpClient);
            var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
            var webClientBuilder = org.springframework.web.reactive.function.client.WebClient.builder()
                    .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient));

            OpenAiApi api = new OpenAiApi(baseUrl, apiKey, restClientBuilder, webClientBuilder);
            ChatModel chatModel = new OpenAiChatModel(api, OpenAiChatOptions.builder()
                    .model(modelName)
                    .maxTokens(4096)
                    .temperature(0.7)
                    .build());
            cloudModels.put(modelName, chatModel);
            log.info("Registered ChatModel: {} (service: {}, timeout: 120s)", modelName, serviceName);
        } catch (Exception e) {
            log.warn("Failed to create ChatModel for {}: {}", modelName, e.getMessage());
        }
    }

    /**
     * Discover available models via the OpenAI-compatible /v1/models endpoint.
     */
    private List<String> discoverModels(String openAiBaseUrl, String apiKey) {
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(openAiBaseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

            String response = client.get()
                    .uri("/v1/models")
                    .retrieve()
                    .body(String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);
            JsonNode data = root.get("data");

            List<String> models = new ArrayList<>();
            if (data != null && data.isArray()) {
                for (JsonNode model : data) {
                    JsonNode idNode = model.get("id");
                    if (idNode != null) {
                        models.add(idNode.asText());
                    }
                }
            }
            log.info("Discovered {} model(s) from {}: {}", models.size(), openAiBaseUrl, models);
            return models;
        } catch (Exception e) {
            log.warn("Failed to discover models from {}/v1/models: {}", openAiBaseUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * Checks if a model name indicates an embedding model (not suitable for chat).
     */
    private boolean isEmbeddingModel(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("embed") || lower.contains("ada-002");
    }

    // === Anthropic Model Factory (local/test profiles) ===

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
