package staticwebserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.*;

/**
 * Apartment Web Server for both local and Clever Cloud environments
 */
public class SimpleHttpServer {

    public static void main(String[] args) throws Exception {
        // ✅ Get the port from environment (Clever Cloud will inject PORT)
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));

        // ✅ Bind to 0.0.0.0 so Clever Cloud router can connect to your app
        InetSocketAddress address = new InetSocketAddress("0.0.0.0", port);
        HttpServer server = HttpServer.create(address, 0);

        // ✅ Create REST API endpoint
        server.createContext("/api/apartments", new ApartmentHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("✅ Server running on http://0.0.0.0:" + port);
    }

    /**
     * ✅ Connect to MySQL database
     * Works with Clever Cloud or local MySQL depending on environment
     */
    static Connection getConnection() throws SQLException {
        // Read environment variables for Clever Cloud
        String host = System.getenv().getOrDefault("MYSQL_ADDON_HOST", "localhost");
        String port = System.getenv().getOrDefault("MYSQL_ADDON_PORT", "3306");
        String db = System.getenv().getOrDefault("MYSQL_ADDON_DB", "apartment");
        String user = System.getenv().getOrDefault("MYSQL_ADDON_USER", "root");
        String pass = System.getenv().getOrDefault("MYSQL_ADDON_PASSWORD", "");

        // Build JDBC URL
        String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC", host, port, db);

        System.out.println("🔗 Connecting to DB: " + jdbcUrl);
        return DriverManager.getConnection(jdbcUrl, user, pass);
    }

    /**
     * ✅ API endpoint to list apartments
     * URL: /api/apartments?q=texas
     */
    static class ApartmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Parse query parameters
            Map<String, String> queryParams = queryToMap(exchange.getRequestURI().getQuery());
            String q = queryParams.getOrDefault("q", "").trim();

            List<Map<String, Object>> results = new ArrayList<>();

            try (Connection conn = getConnection()) {
                String sql = "SELECT * FROM apartment_details";
                if (!q.isEmpty()) {
                    sql += " WHERE state LIKE ?";
                }

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    if (!q.isEmpty()) {
                        stmt.setString(1, "%" + q + "%");
                    }

                    ResultSet rs = stmt.executeQuery();
                    ResultSetMetaData meta = rs.getMetaData();

                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(meta.getColumnName(i), rs.getObject(i));
                        }
                        results.add(row);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
                return;
            }

            // Convert results to JSON-like format
            String jsonResponse = listToJson(results);
            sendResponse(exchange, 200, jsonResponse);
        }
    }

    /**
     * ✅ Converts list of maps to JSON
     */
    private static String listToJson(List<Map<String, Object>> list) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> map = list.get(i);
            json.append("{");
            int j = 0;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                json.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value == null) {
                    json.append("null");
                } else if (value instanceof Number) {
                    json.append(value);
                } else {
                    json.append("\"").append(value.toString().replace("\"", "\\\"")).append("\"");
                }
                if (++j < map.size()) json.append(",");
            }
            json.append("}");
            if (i < list.size() - 1) json.append(",");
        }
        json.append("]");
        return json.toString();
    }

    /**
     * ✅ Sends a JSON response with proper headers
     */
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*"); // Enable CORS
        exchange.sendResponseHeaders(statusCode, response.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }

    /**
     * ✅ Parse URL query parameters (e.g. ?q=texas)
     */
    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) return result;

        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(entry[0], entry[1]);
            } else {
                result.put(entry[0], "");
            }
        }
        return result;
    }
}
