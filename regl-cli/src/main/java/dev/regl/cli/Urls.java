package dev.regl.cli;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Reads a schema or document from a filesystem path or an {@code http(s)} URL.
 *
 * <p>Load failures are {@link IllegalArgumentException} — exit code 2, "nothing
 * was validated" (§14 of the document spec) — never a crash and never a
 * best-effort partial read.
 */
final class Urls {

    /** What was read, plus the response's {@code Content-Type} for a remote URL. */
    record Loaded(String text, String contentType) {
    }

    private Urls() {
    }

    static boolean isRemote(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /**
     * Reads {@code url}: a filesystem path, or an {@code http(s)} URL fetched
     * once with redirects followed and a 10-second timeout.
     *
     * @throws IllegalArgumentException if the path does not exist, the URL is
     *                                  unreachable, or the response is not 2xx
     */
    static Loaded read(String url) {
        if (isRemote(url)) {
            return readRemote(url);
        }
        String text;
        try {
            text = Files.readString(Path.of(url));
        } catch (java.nio.file.NoSuchFileException e) {
            throw new IllegalArgumentException("File not found: " + url);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not read '" + url + "': " + e.getMessage(), e);
        }
        return new Loaded(text, null);
    }

    private static Loaded readRemote(String url) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not fetch '" + url + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Fetching '" + url + "' was interrupted.", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException(
                    "Fetching '" + url + "' returned HTTP " + response.statusCode() + ".");
        }
        return new Loaded(response.body(), response.headers().firstValue("content-type").orElse(null));
    }

    /** The part of a URL or path after the last {@code /}, for extension checks. */
    static String fileName(String url) {
        int slash = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
        return slash < 0 ? url : url.substring(slash + 1);
    }
}