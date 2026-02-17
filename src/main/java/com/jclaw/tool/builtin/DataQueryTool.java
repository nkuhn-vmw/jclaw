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

        // Only allow SELECT statements
        String trimmed = query.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT")) {
            log.warn("Rejected non-SELECT query: {}", query);
            return "{\"error\": \"Only SELECT queries are allowed\"}";
        }

        // Block dangerous patterns
        if (trimmed.contains("DROP") || trimmed.contains("DELETE") ||
            trimmed.contains("INSERT") || trimmed.contains("UPDATE") ||
            trimmed.contains("ALTER") || trimmed.contains("TRUNCATE")) {
            return "{\"error\": \"Query contains disallowed SQL keywords\"}";
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(MAX_ROWS);
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
}
