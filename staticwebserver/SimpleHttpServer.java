package staticwebserver;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

        String url = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC", host, port, db);

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("MySQL JDBC Driver not found on classpath: " + e.getMessage());
        }

        return DriverManager.getConnection(url, user, pass);
    }

    // Handler that serves static files from /app/app on disk if available; otherwise from classpath /app/*
    static class StaticHandler implements HttpHandler {
        private final Path webRoot = Paths.get("/app/app"); // container disk location
        private final Map<String,String> contentTypes = defaultContentTypes();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = sanitizePath(exchange.getRequestURI().getPath());

            // Default to index.html for root or directory
            if ("/".equals(path) || path.isEmpty()) path = "/index.html";

            // Try disk first: /app/app + path
            Path candidate = webRoot.resolve(path.substring(1)).normalize();
            if (candidate.startsWith(webRoot) && Files.exists(candidate) && !Files.isDirectory(candidate)) {
                serveFile(exchange, candidate);
                return;
            }

            // Fallback: load from classpath (inside jar) at /app/...
            InputStream is = SimpleHttpServer.class.getResourceAsStream("/app" + path);
            if (is != null) {
                byte[] bytes = readAllBytes(is);
                String ct = contentTypeFor(path);
                exchange.getResponseHeaders().set("Content-Type", ct);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            // Not found - send 404
            String msg = "404 Not Found";
            exchange.sendResponseHeaders(404, msg.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg.getBytes(StandardCharsets.UTF_8));
            }
        }

        private void serveFile(HttpExchange exchange, Path file) throws IOException {
            long size = Files.size(file);
            String ct = contentTypeFor(file.getFileName().toString());
            exchange.getResponseHeaders().set("Content-Type", ct);
            exchange.sendResponseHeaders(200, size);
            try (OutputStream os = exchange.getResponseBody(); InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
            }
        }

        private String sanitizePath(String p) {
            // remove query, keep leading slash
            if (p == null) return "/";
            int q = p.indexOf('?');
            if (q >= 0) p = p.substring(0, q);
            return p.replaceAll("/+", "/");
        }

        private static byte[] readAllBytes(InputStream is) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        }

        private String contentTypeFor(String path) {
            String ext = "";
            int idx = path.lastIndexOf('.');
            if (idx >= 0) ext = path.substring(idx + 1).toLowerCase();
            return contentTypes.getOrDefault(ext, "application/octet-stream");
        }

        private static Map<String,String> defaultContentTypes() {
            Map<String,String> m = new HashMap<>();
            m.put("html", "text/html; charset=utf-8");
            m.put("htm", "text/html; charset=utf-8");
            m.put("css", "text/css; charset=utf-8");
            m.put("js", "application/javascript; charset=utf-8");
            m.put("json", "application/json; charset=utf-8");
            m.put("png", "image/png");
            m.put("jpg", "image/jpeg");
            m.put("jpeg", "image/jpeg");
            m.put("svg", "image/svg+xml");
            m.put("ico", "image/x-icon");
            m.put("txt", "text/plain; charset=utf-8");
            return m;
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

                URI uri = exchange.getRequestURI();
                Map<String, String> q = queryToMap(uri.getRawQuery());
                String qTerm = q.getOrDefault("q", "").trim();

                List<Map<String, Object>> rows = new ArrayList<>();

                try (Connection conn = getConnection()) {
                    String sql;
                    if (qTerm.isEmpty()) {
                        sql = "SELECT id, name, address, state, min_rent, max_rent, sqft, bed, bath FROM apartment_details LIMIT 200";
                        try (PreparedStatement ps = conn.prepareStatement(sql);
                             ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) rows.add(rowFromRs(rs));
                        }
                    } else {
                        sql = "SELECT id, name, address, state, min_rent, max_rent, sqft, bed, bath " +
                                "FROM apartment_details WHERE state LIKE ? OR name LIKE ? LIMIT 200";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            String like = "%" + qTerm + "%";
                            ps.setString(1, like);
                            ps.setString(2, like);
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) rows.add(rowFromRs(rs));
                            }
                        }
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                    String err = "{\"error\":\"DB error: " + escapeJson(ex.getMessage()) + "\"}";
                    byte[] bytes = err.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(500, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                    return;
                }

                String json = toJsonArray(rows);
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                String err = "{\"error\":\"" + escapeJson(e.getMessa
