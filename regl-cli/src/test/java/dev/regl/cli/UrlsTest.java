package dev.regl.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL loading (§14): a schema or document is a filesystem path or an
 * {@code http(s)} URL, fetched once with redirects followed. The local tests use
 * the JDK's own HTTP server, so the remote path is exercised for real without a
 * dependency or a network.
 */
class UrlsTest {

    private HttpServer server;

    private String serve(String path, String contentType, String body) throws Exception {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
        }
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        return base + path;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static final String SCHEMA = """
            { "fields": { "name": { "type": "STRING", "rules": [ "notNull", { "minLength": 3 } ] } } }
            """;

    private int validate(String schema, String document, StringBuilder out, StringBuilder err) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit = Regl.validate(schema, document, null, false, false, false,
                new PrintStream(outBytes), new PrintStream(errBytes));
        out.append(outBytes.toString().trim());
        err.append(errBytes.toString().trim());
        return exit;
    }

    @Test
    @DisplayName("a schema and a document can both be http URLs")
    void remoteSchemaAndDocument() throws Exception {
        String schema = serve("/schema.json", "application/json", SCHEMA);
        String document = serve("/doc.json", "application/json", "{\"name\":\"ab\"}");

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int exit = validate(schema, document, out, err);

        assertEquals(1, exit);
        assertTrue(out.toString().contains("name minLength"), out.toString());
        assertTrue(err.isEmpty(), "a validation run prints nothing to stderr: " + err);
    }

    @Test
    @DisplayName("a URL with no extension is detected from Content-Type")
    void contentTypeDetection() throws Exception {
        String schema = serve("/conf", "application/json", SCHEMA);
        String document = serve("/document", "application/json", "{\"name\":\"api\"}");

        StringBuilder out = new StringBuilder();
        int exit = validate(schema, document, out, new StringBuilder());

        assertEquals(0, exit);
        assertEquals("ok", out.toString());
    }

    @Test
    @DisplayName("a YAML Content-Type is detected, including charset parameters")
    void yamlContentType() throws Exception {
        String schema = serve("/schema", "application/json", SCHEMA);
        String document = serve("/doc", "application/yaml; charset=utf-8", "name: ab\n");

        StringBuilder out = new StringBuilder();
        int exit = validate(schema, document, out, new StringBuilder());

        assertEquals(1, exit);
        assertTrue(out.toString().contains("name minLength"), out.toString());
    }

    @Test
    @DisplayName("a non-2xx response is a load error: exit 2, nothing validated")
    void httpError() throws Exception {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
        }
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/missing", exchange -> exchange.sendResponseHeaders(404, -1));

        String schema = serve("/schema.json", "application/json", SCHEMA);
        StringBuilder err = new StringBuilder();
        int exit = validate(schema, base + "/missing", new StringBuilder(), err);

        assertEquals(2, exit);
        assertTrue(err.toString().contains("HTTP 404"), err.toString());
    }

    @Test
    @DisplayName("an unreachable host is a load error, not a stack trace")
    void unreachable() {
        StringBuilder err = new StringBuilder();
        int exit = validate("http://127.0.0.1:1/schema.json", "http://127.0.0.1:1/doc.json",
                new StringBuilder(), err);

        assertEquals(2, exit);
        assertTrue(err.toString().startsWith("regl: "), err.toString());
    }

    @Test
    @DisplayName("a local path still loads")
    void localPath(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema.json");
        Files.writeString(schema, SCHEMA);
        Path document = dir.resolve("doc.json");
        Files.writeString(document, "{\"name\":\"api\"}");

        StringBuilder out = new StringBuilder();
        int exit = validate(schema.toString(), document.toString(), out, new StringBuilder());

        assertEquals(0, exit);
        assertEquals("ok", out.toString());
    }

    @Test
    @DisplayName("a missing local file is a load error naming the file")
    void missingFile(@org.junit.jupiter.api.io.TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Urls.read(dir.resolve("nope.json").toString()));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }

    @Test
    @DisplayName("format detection from extensions and Content-Type headers")
    void detection() {
        assertEquals(Format.JSON, Format.ofExtension("schema.json"));
        assertEquals(Format.YAML, Format.ofExtension("conf.YAML"));
        assertEquals(Format.YAML, Format.ofExtension("conf.yml"));
        assertEquals(Format.PROPERTIES, Format.ofExtension("app.properties"));
        assertNull(Format.ofExtension("config"));
        assertNull(Format.ofExtension("config.xml"));

        assertEquals(Format.JSON, Format.ofContentType("application/json"));
        assertEquals(Format.JSON, Format.ofContentType("application/json; charset=utf-8"));
        assertEquals(Format.YAML, Format.ofContentType("application/yaml"));
        assertEquals(Format.YAML, Format.ofContentType("text/x-yaml"));
        assertEquals(Format.PROPERTIES, Format.ofContentType("text/x-java-properties"));
        assertNull(Format.ofContentType("text/plain"));
        assertNull(Format.ofContentType(null));

        assertEquals(Format.YAML, Format.parse("yaml"));
        assertEquals(Format.JSON, Format.parse("JSON"));
        assertThrows(IllegalArgumentException.class, () -> Format.parse("xml"));
    }

    @Test
    @DisplayName("a fetched response keeps its Content-Type for format detection")
    void loadedCarriesContentType() throws Exception {
        String url = serve("/doc", "application/json", "{}");
        Urls.Loaded loaded = Urls.read(url);
        assertEquals("{}", loaded.text());
        assertEquals("application/json", loaded.contentType());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("fileName splits a URL at its last slash for extension checks")
    void fileName() {
        assertEquals("doc.json", Urls.fileName("https://example.com/dir/doc.json"));
        assertEquals("doc.json", Urls.fileName("relative/dir/doc.json"));
        assertEquals("doc.json", Urls.fileName("doc.json"));
        assertEquals("windows.json", Urls.fileName("dir\\windows.json"));
    }
}