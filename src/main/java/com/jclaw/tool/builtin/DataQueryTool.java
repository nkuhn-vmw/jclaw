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
import java.util.Set;
import java.util.regex.Pattern;

@Component
@JclawTool(
        name = "data_query",
        description = "Query bound database services with read-only SQL queries.",
        riskLevel = RiskLevel.MEDIUM,
        requiresApproval = false
)
public class DataQueryTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DataQueryTool.class);
    private static final int MAX_ROWS = 100;
    private static final Pattern DANGEROUS_KEYWORD = Pattern.compile(
            "\\b(DROP|DELETE|INSERT|UPDATE|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|EXEC|EXECUTE|CALL)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> INTERNAL_TABLES = Set.of(
            "audit_log", "identity_mappings", "sessions", "session_messages",
            "agent_configs", "agent_allowed_tools", "agent_denied_tools",
            "agent_egress_allowlist", "scheduled_tasks", "flyway_schema_history");
    private static final Set<String> BLOCKED_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "pg_tables", "pg_views",
            "pg_columns", "pg_stat", "sys.tables", "sys.columns",
            "all_tables", "all_tab_columns", "dba_tables");

    private final DataSource dataSource;

    public DataQueryTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String call(String toolInput) {
        String query = com.jclaw.tool.ToolInputParser.getString(toolInput, "query");
        if (query == null || query.isBlank()) {
            return "{\"error\": \"query is required\"}";
        }

        int maxRows = com.jclaw.tool.ToolInputParser.getInt(toolInput, "maxRows", MAX_ROWS);
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

        // Block queries against database catalog/metadata schemas
        String lowerQuery = withoutStrings.toLowerCase();
        for (String schema : BLOCKED_SCHEMAS) {
            if (lowerQuery.contains(schema)) {
                log.warn("Rejected query referencing catalog/metadata schema {}: {}", schema, query);
                return "{\"error\": \"Queries against database catalog schemas are not allowed\"}";
            }
        }

        // Block queries against jclaw internal tables
        for (String table : INTERNAL_TABLES) {
            if (lowerQuery.contains(table)) {
                log.warn("Rejected query referencing internal table {}: {}", table, query);
                return "{\"error\": \"Query references internal table: " + table + "\"}";
            }
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
            String errMsg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "query execution failed";
            log.error("Data query failed: {}", errMsg);
            return "{\"error\": \"" + errMsg + "\"}";
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
                sb.append("\"").append(escapeJson(meta.getColumnLabel(i))).append("\":");
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

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

}
