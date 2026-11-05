package com.example.notification.sender;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Thin shared HTTP POST helper for provider adapters. Fails with ProviderException on non-2xx or I/O errors. */
@Component
public class ProviderHttp {

    private final HttpClient client;
    private final Duration timeout;

    @Autowired
    public ProviderHttp(ProviderProperties props) {
        this(props.timeout());
    }

    public ProviderHttp(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    public void post(String provider, URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        HttpResponse<String> res;
        try {
            res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException(provider + " request failed: " + e.getClass().getSimpleName(), -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(provider + " request interrupted", -1);
        }
        int code = res.statusCode();
        if (code < 200 || code >= 300) {
            String text = res.body() == null ? "" : res.body();
            throw new ProviderException(provider + " returned HTTP " + code + ": "
                    + text.substring(0, Math.min(text.length(), 200)), code);
        }
    }
}
