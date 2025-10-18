package staticwebserver;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class SimpleHttpServer {

    // Local defaults (used only when env vars are not present)
    private static final String DEFAULT_DB_HOST = "localhost";
    private static final String DEFAULT_DB_PORT = "3306";
    private static final String DEFAULT_DB_NAME = "apartment";
    private static final String DEFAULT_DB_USER = "root";
    private static final String DEFAULT_DB_PASS = "";

    public static void main(String[] args) throws Exception {
        // Get port from env (Clever Cloud sets PORT); default to 8080
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        // Bind to 0.0.0.0 so container is accessible externally
        InetSocketAddress address = new InetSocketAddress("0.0.0.0", port);

        HttpServer server = HttpServer.create(address, 0);
        System.out.println("? Server running on http://0.0.0.0:" + port);

        server.createContext("/api/apartments", new ApartmentHandler());
        server.createContext("/", new StaticHandler());

        server.setExecutor(null);
        server.start();
    }

    // Tries multiple env var names provided by Clever Cloud add-on and falls back to defaults
    static Connection getConnection() throws SQLException {
        // Clever Cloud provides MYSQL_ADDON_* (see your env vars)
        String host = System.getenv().getOrDefault("MYSQL_ADDON_HOST",
                System.getenv().getOrDefault("DB_HOST", DEFAULT_DB_HOST));
        String port = System.getenv().getOrDefault("MYSQL_ADDON_PORT",
                System.getenv().getOrDefault("DB_PORT", DEFAULT_DB_PORT));
        String db   = System.getenv().getOrDefault("MYSQL_ADDON_DB",
                System.getenv().getOrDefault("DB_NAME", DEFAULT_DB_NAME));
        String user = System.getenv().getOrDefault("MYSQL_ADDON_USER",
                System.getenv().getOrDefault("DB_USER", DEFAULT_DB_USER));
        String pass = System.getenv().getOrDefault("MYSQL_ADDON_PASSWORD",
                System.getenv().getOrDefault("DB_PASS", DEFAULT_DB_PASS));

        // Build JDBC URL
        String url = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC", host, port, db);

        // Ensure driver loaded (modern drivers auto-register, but loading is harmless)
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            // If driver is not present, this will help debugging
            System.err.println("MySQL JDBC Driver not found on classpath: " + e.getMessage());
        }

        return DriverManager.getConnection(url, user, pass);
    }

    // Handle static files (if you serve index.html) - minimal
    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path)) {
                InputStream is = SimpleHttpServer.class.getResourceAsStream("/app/index.html");
                byte[] bytes;
                if (is != null) {
                    bytes = is.readAllBytes();
                } else {
                    String html = "<html><body><h3>Apartment app</h3><p>Use /api/apartments?q=...</p></body></html>";
                    bytes = html.getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                // Not found
                String msg = "404 Not Found";
                exchange.sendResponseHeaders(404, msg.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(msg.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    static class ApartmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                // parse query params
                URI uri = exchange.getRequestURI();
                Map<String, String> q = queryToMap(uri.getRawQuery());
                String qTerm = q.getOrDefault("q", "").trim();

                List<Map<String, Object>> rows = new ArrayList<>();

                // Query DB
                try (Connection conn = getConnection()) {
                    String sql;
                    if (qTerm.isEmpty()) {
                        sql = "SELECT id, name, address, state, min_rent, max_rent, sqft, bed, bath FROM apartment_details LIMIT 200";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    rows.add(rowFromRs(rs));
                                }
                            }
                        }
                    } else {
                        sql = "SELECT id, name, address, state, min_rent, max_rent, sqft, bed, bath " +
                                "FROM apartment_details WHERE state LIKE ? OR name LIKE ? LIMIT 200";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            String like = "%" + qTerm + "%";
                            ps.setString(1, like);
                            ps.setString(2, like);
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    rows.add(rowFromRs(rs));
                                }
                            }
                        }
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                    // Return 500 with message
                    String err = "{\"error\":\"DB error: " + escapeJson(ex.getMessage()) + "\"}";
                    byte[] bytes = err.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(500, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                    return;
                }

                // Convert rows to JSON (simple serializer)
                String json = toJsonArray(rows);
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                String err = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                byte[] bytes = err.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(500, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
        }

        private Map<String, Object> rowFromRs(ResultSet rs) throws SQLException {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id"));
            row.put("name", rs.getString("name"));
            row.put("address", rs.getString("address"));
            row.put("state", rs.getString("state"));
            row.put("min_rent", rs.getString("min_rent"));
            row.put("max_rent", rs.getString("max_rent"));
            row.put("sqft", rs.getString("sqft"));
            row.put("bed", rs.getString("bed"));
            row.put("bath", rs.getString("bath"));
            return row;
        }
    }

    // Utility: parse query string into map
    static Map<String,String> queryToMap(String query) {
        if (query == null || query.isEmpty()) return Collections.emptyMap();
        return Arrays.stream(query.split("&"))
                .map(s -> s.split("=",2))
                .collect(Collectors.toMap(
                        a -> urlDecode(a[0]),
                        a -> a.length>1 ? urlDecode(a[1]) : ""
                                         ));
    }

    // Simple JSON array serializer for list of maps
    static String toJsonArray(List<Map<String,Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean firstRow = true;
        for (Map<String,Object> row : rows) {
            if (!firstRow) sb.append(',');
            firstRow = false;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String,Object> e : row.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(e.getKey())).append('"').append(':');
                Object v = e.getValue();
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v.toString());
                } else {
                    sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
                }
            }
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    // URL decode
    static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // Escape strings for JSON minimal
    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }
}
