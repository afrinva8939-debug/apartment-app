package staticwebserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.*;

public class SimpleHttpServer {

    private static final int port = 8000;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/apartments", new ApartmentHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("✅ Server running on http://localhost:" + port);
    }

    /**
     * Establishes a connection to MySQL database.
     * Works both locally and on Clever Cloud using environment variables.
     */
    static Connection getConnection() throws SQLException {
        // Read Clever Cloud environment variables
        String host = System.getenv().getOrDefault("MYSQL_ADDON_HOST", "localhost");
        String port = System.getenv().getOrDefault("MYSQL_ADDON_PORT", "3306");
        String db = System.getenv().getOrDefault("MYSQL_ADDON_DB", "apartment_db");
        String user = System.getenv().getOrDefault("MYSQL_ADDON_USER", "root");
        String pass = System.getenv().getOrDefault("MYSQL_ADDON_PASSWORD", "");

        String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC", host, port, db);

        System.out.println("Connecting to DB: " + jdbcUrl);
        return DriverManager.getConnection(jdbcUrl, user, pass);
    }

    /**
     * Handles /api/apartments endpoint.
     */
    static class ApartmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
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

            // Convert results to JSON-like string
            String jsonResponse = results.toString().replace("=", ":");
            sendResponse(exchange, 200, jsonResponse);
        }
    }

    /**
     * Utility: send a JSON response
     */
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    /**
     * Utility: parse URL query parameters
     */
    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;

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
