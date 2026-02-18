package com.jclaw.tool.builtin;

import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.regex.Pattern;

@Component
@JclawTool(
        name = "data_query",
        description = "Query bound database services with read-only SQL queries.",
        riskLevel = RiskLevel.MEDIUM,
        requiresApproval = true
)
public class DataQueryTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DataQueryTool.class);
    private static final int MAX_ROWS = 100;
    private static final Pattern DANGEROUS_KEYWORD = Pattern.compile(
            "\\b(DROP|DELETE|INSERT|UPDATE|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|EXEC|EXECUTE|CALL)\\b",
            Pattern.CASE_INSENSITIVE);

    private final DataSource dataSource;

    public DataQueryTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String call(String toolInput) {
        String query = extractField(toolInput, "query");
        if (query == null || query.isBlank()) {
            return "{\"error\": \"query is required\"}";
        }

        int maxRows = parseIntField(toolInput, "maxRows", MAX_ROWS);
        if (maxRows < 1) maxRows = 1;
        if (maxRows > MAX_ROWS) maxRows = MAX_ROWS;

        // Only allow SELECT statements
        String trimmed = query.trim();
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            log.warn("Rejected non-SELECT query: {}", query);
            return "{\"error\": \"Only SELECT queries are allowed\"}";
        }

        // Block semicolons to prevent statement chaining
        if (trimmed.contains(";")) {
            log.warn("Rejected query with semicolon (statement chaining): {}", query);
            return "{\"error\": \"Semicolons are not allowed in queries\"}";
        }

        // Strip string literals before checking for dangerous keywords to avoid false positives
        String withoutStrings = trimmed.replaceAll("'[^']*'", "''");
        if (DANGEROUS_KEYWORD.matcher(withoutStrings).find()) {
            log.warn("Rejected query with dangerous keyword: {}", query);
            return "{\"error\": \"Query contains disallowed SQL keywords\"}";
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(maxRows);
                stmt.setQueryTimeout(10);
                ResultSet rs = stmt.executeQuery(query);
                return resultSetToJson(rs);
            } finally {
                conn.rollback(); // ensure no side effects
            }
        } catch (Exception e) {
            log.error("Data query failed: {}", e.getMessage());
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("data_query")
                .description("Query bound database services with read-only SQL")
                .inputSchema("""
                    {"type":"object","properties":{
                      "query":{"type":"string","description":"A read-only SQL SELECT query"},
                      "maxRows":{"type":"integer","description":"Maximum rows to return","default":100}
                    },"required":["query"]}""")
                .build();
    }

    private String resultSetToJson(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        while (rs.next()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            for (int i = 1; i <= cols; i++) {
                if (i > 1) sb.append(",");
                sb.append("\"").append(meta.getColumnLabel(i)).append("\":");
                Object val = rs.getObject(i);
                if (val == null) {
                    sb.append("null");
                } else if (val instanceof Number) {
                    sb.append(val);
                } else {
                    sb.append("\"").append(val.toString().replace("\"", "\\\"")).append("\"");
                }
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String extractField(String json, String field) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return null;
        int start = json.indexOf("\"", idx + field.length() + 2);
        if (start < 0) return null;
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private int parseIntField(String json, String field, int defaultValue) {
        if (json == null) return defaultValue;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(":", idx);
        if (colonIdx < 0) return defaultValue;
        StringBuilder num = new StringBuilder();
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t') continue;
            if (Character.isDigit(c)) {
                num.append(c);
            } else {
                break;
            }
        }
        if (num.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(num.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
