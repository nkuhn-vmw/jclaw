package com.jclaw.tool.builtin;

import com.jclaw.config.SecretsConfig;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@JclawTool(
        name = "web_search",
        description = "Search the web for current information. Returns relevant search results.",
        riskLevel = RiskLevel.LOW,
        requiresApproval = false
)
public class WebSearchTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final WebClient webClient;
    private final SecretsConfig secretsConfig;
    private final String searchProvider;

    public WebSearchTool(SecretsConfig secretsConfig,
                        @Value("${jclaw.search.provider:serpapi}") String searchProvider) {
        this.secretsConfig = secretsConfig;
        this.searchProvider = searchProvider;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public String call(String toolInput) {
        String query = extractField(toolInput, "query");
        if (query == null || query.isBlank()) {
            return "{\"error\": \"query is required\"}";
        }

        String apiKey = secretsConfig.getSearchApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Web search requested but no API key configured");
            return String.format(
                "{\"query\":\"%s\",\"results\":[],\"message\":\"Web search provider not configured. " +
                "Set search-api-key in jclaw-secrets service binding.\"}",
                query.replace("\"", "'"));
        }

        log.info("Web search: provider={} query={}", searchProvider, query);

        try {
            String result;
            if ("brave".equalsIgnoreCase(searchProvider)) {
                result = searchBrave(query, apiKey);
            } else {
                result = searchSerpApi(query, apiKey);
            }
            return result;
        } catch (Exception e) {
            log.error("Web search failed: provider={} query={}", searchProvider, query, e);
            return String.format(
                "{\"query\":\"%s\",\"error\":\"%s\"}",
                query.replace("\"", "'"),
                e.getMessage().replace("\"", "'"));
        }
    }

    private String searchSerpApi(String query, String apiKey) {
        return webClient.get()
                .uri("https://serpapi.com/search", uriBuilder -> uriBuilder
                        .queryParam("q", query)
                        .queryParam("api_key", apiKey)
                        .queryParam("engine", "google")
                        .queryParam("num", 5)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String searchBrave(String query, String apiKey) {
        return webClient.get()
                .uri("https://api.search.brave.com/res/v1/web/search", uriBuilder -> uriBuilder
                        .queryParam("q", query)
                        .queryParam("count", 5)
                        .build())
                .header("X-Subscription-Token", apiKey)
                .header("Accept", "application/json")
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("web_search")
                .description("Search the web for current information")
                .inputSchema("""
                    {"type":"object","properties":{
                      "query":{"type":"string","description":"The search query"},
                      "maxResults":{"type":"integer","description":"Maximum results to return","default":5}
                    },"required":["query"]}""")
                .build();
    }

    private String extractField(String json, String field) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return json.trim().replaceAll("^\"|\"$", "");
        int start = json.indexOf("\"", idx + field.length() + 2);
        if (start < 0) return null;
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }
}
