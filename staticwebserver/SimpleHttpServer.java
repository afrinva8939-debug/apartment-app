package staticwebserver;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class SimpleHttpServer {

    // Default local settings (used only when env vars are not present)
    private static final String DEFAULT_DB_HOST = "localhost";
    private static final String DEFAULT_DB_PORT = "3306";
    private static final String DEFAULT_DB_NAME = "apartment";
    private static final String DEFAULT_DB_USER = "root";
    private static final String DEFAULT_DB_PASS = "";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/apartments", new ApartmentHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("? Server running on http://localhost:" + port);
    }

    static Connection getConnection() throws SQLException {
        // read from environment variables
        String host = System.getenv().getOrDefault("DB_HOST", DEFAULT_DB_HOST);
        String port = System.getenv().getOrDefault("DB_PORT", DEFAULT_DB_PORT);
        String name = System.getenv().getOrDefault("DB_NAME", DEFAULT_DB_NAME);
        String user = System.getenv().getOrDefault("DB_USER", DEFAULT_DB_USER);
        String pass = System.getenv().getOrDefault("DB_PASS", DEFAULT_DB_PASS);

        String url = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC", host, port, name);
        // Ensure driver exists on classpath (mysql connector JAR)
        return DriverManager.getConnection(url, user, pass);
    }

    static class ApartmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String,String> qparams = queryToMap(exchange.getRequestURI().getQuery());
            String q = qparams.getOrDefault("q","").trim();

            List<Map<String,Object>> rows = new ArrayList<>();
            try (Connection conn = getConnection()) {
                String sql = "SELECT id, apt_result_apartment_name, apt_result_address, apt_result_min_rent, apt_result_max_rent, apt_result_sqft, apt_result_bed, apt_result_bath, state FROM apartment_details"
                        + (q.isEmpty() ? " LIMIT 50" : " WHERE state LIKE ? OR apt_result_address LIKE ? OR apt_result_apartment_name LIKE ? LIMIT 100");
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    if (!q.isEmpty()) {
                        String like = "%" + q + "%";
                        ps.setString(1, like);
                        ps.setString(2, like);
                        ps.setString(3, like);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData md = rs.getMetaData();
                        while (rs.next()) {
                            Map<String,Object> row = new LinkedHashMap<>();
                            for (int i=1;i<=md.getColumnCount();i++) {
                                row.put(md.getColumnLabel(i), rs.getObject(i));
                            }
                            rows.add(row);
                        }
                    }
                }
            } catch (SQLException ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, Collections.singletonMap("error", ex.getMessage()));
                return;
            }

            sendJson(exchange,200, rows);
        }

        private Map<String,String> queryToMap(String query) {
            if (query==null || query.isEmpty()) return Collections.emptyMap();
            return Arrays.stream(query.split("&"))
                    .map(kv -> kv.split("=",2))
                    .collect(Collectors.toMap(
                            k -> urlDecode(k[0]),
                            k -> k.length>1 ? urlDecode(k[1]) : ""
                                             ));
        }
        private String urlDecode(String s) {
            try { return URLDecoder.decode(s,"UTF-8"); } catch (Exception e){ return s; }
        }

        private void sendJson(HttpExchange exchange, int code, Object value) throws IOException {
            String json = toJson(value);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
            byte[] bytes = json.getBytes("UTF-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }

        // tiny JSON generator (avoids extra dependency)
        @SuppressWarnings("unchecked")
        private String toJson(Object o){
            if (o==null) return "null";
            if (o instanceof Map) {
                Map<String,Object> m = (Map)o;
                return "{" + m.entrySet().stream()
                        .map(e -> quote(e.getKey()) + ":" + toJson(e.getValue()))
                        .collect(Collectors.joining(",")) + "}";
            }
            if (o instanceof Collection) {
                Collection c = (Collection)o;
                return "[" + c.stream().map(this::toJson).collect(Collectors.joining(",")) + "]";
            }
            if (o instanceof Number || o instanceof Boolean) return o.toString();
            return quote(String.valueOf(o));
        }
        private String quote(String s) {
            String esc = s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
            return "\"" + esc + "\"";
        }
    }
}
