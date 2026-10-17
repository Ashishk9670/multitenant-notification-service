package com.example.notification.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/** JDK HttpClient transport. Redirects are never followed (a redirect could point at an internal address). */
@Component
public class HttpWebhookTransport implements WebhookTransport {

    private final HttpClient client;
    private final WebhookProperties props;

    public HttpWebhookTransport(WebhookProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(props.timeout())
                .build();
    }

    @Override
    public int post(URI uri, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(props.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
