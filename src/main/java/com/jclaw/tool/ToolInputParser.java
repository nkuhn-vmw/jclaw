package com.jclaw.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared utility for parsing tool input JSON from LLM-generated tool calls.
 * Uses Jackson for robust JSON parsing instead of brittle string manipulation.
 */
public final class ToolInputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolInputParser() {}

    /**
     * Parses the tool input string as a JSON object.
     * Returns null if the input is null, blank, or not valid JSON.
     */
    public static JsonNode parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(toolInput);
            return node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Extracts a string field from the tool input JSON.
     * Returns null if the field is missing or not a text value.
     */
    public static String getString(String toolInput, String field) {
        JsonNode root = parse(toolInput);
        if (root == null) return null;
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        return value.asText();
    }

    /**
     * Extracts an integer field from the tool input JSON.
     * Returns the default value if the field is missing or not a number.
     */
    public static int getInt(String toolInput, String field, int defaultValue) {
        JsonNode root = parse(toolInput);
        if (root == null) return defaultValue;
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return defaultValue;
        if (value.isNumber()) return value.intValue();
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
