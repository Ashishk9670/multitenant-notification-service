package com.example.notification.sender;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import com.example.notification.model.Priority;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

@WireMockTest
class ProviderAdaptersWireMockTest {

    ProviderHttp http = new ProviderHttp(Duration.ofSeconds(20));

    private static DeliveryEvent event(Channel ch, String to) {
        return new DeliveryEvent("n1", "acme", ch, Priority.NORMAL, to, "Hello", "Body & more", 0, Instant.now(), null);
    }

    private static ProviderProperties props(ProviderProperties.Email e, ProviderProperties.Sms s, ProviderProperties.Push p) {
        return new ProviderProperties(e, s, p, Duration.ofSeconds(3));
    }

    private static final ProviderProperties.Email NO_EMAIL =
            new ProviderProperties.Email("stub", new ProviderProperties.Ses(null, null, null, null, null), new ProviderProperties.Smtp(null));
    private static final ProviderProperties.Sms NO_SMS = new ProviderProperties.Sms("stub", new ProviderProperties.Twilio(null, null, null, "x"));
    private static final ProviderProperties.Push NO_PUSH = new ProviderProperties.Push("stub", new ProviderProperties.Fcm(null, null, "x"));

    // ---- Twilio ----

    private TwilioSmsSender twilio(WireMockRuntimeInfo wm) {
        return new TwilioSmsSender(props(NO_EMAIL, new ProviderProperties.Sms("twilio",
                new ProviderProperties.Twilio("AC123", "tok", "+15550001111", wm.getHttpBaseUrl())), NO_PUSH), http);
    }

    @Test
    void twilioPostsFormWithBasicAuth(WireMockRuntimeInfo wm) {
        stubFor(post("/2010-04-01/Accounts/AC123/Messages.json").willReturn(aResponse().withStatus(201).withBody("{}")));
        twilio(wm).send(event(Channel.SMS, "+15552223333"));
        String basic = "Basic " + Base64.getEncoder().encodeToString("AC123:tok".getBytes());
        verify(postRequestedFor(urlEqualTo("/2010-04-01/Accounts/AC123/Messages.json"))
                .withHeader("Authorization", equalTo(basic))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("To=%2B15552223333"))
                .withRequestBody(containing("From=%2B15550001111"))
                .withRequestBody(containing("Body=Body+%26+more")));
    }

    @Test
    void twilioErrorsBecomeRetryableProviderExceptions(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathMatching("/2010-04-01/.*")).willReturn(aResponse().withStatus(503).withBody("down")));
        assertThatThrownBy(() -> twilio(wm).send(event(Channel.SMS, "+1")))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(503);
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getMessage()).contains("503").doesNotContain("tok");
                });
    }

    @Test
    void clientErrorsAreNotRetryable(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathMatching("/2010-04-01/.*")).willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> twilio(wm).send(event(Channel.SMS, "+1")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void twilioRequiresCredentials() {
        assertThatThrownBy(() -> new TwilioSmsSender(props(NO_EMAIL, new ProviderProperties.Sms("twilio",
                new ProviderProperties.Twilio("AC1", null, "+1", "http://x")), NO_PUSH), http))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth-token");
    }

    // ---- FCM ----

    @Test
    void fcmPostsV1MessageWithBearerToken(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/projects/my-proj/messages:send").willReturn(okJson("{\"name\":\"m1\"}")));
        var fcm = new FcmPushSender(props(NO_EMAIL, NO_SMS, new ProviderProperties.Push("fcm",
                new ProviderProperties.Fcm("my-proj", "ya29.token", wm.getHttpBaseUrl()))), http);
        fcm.send(event(Channel.PUSH, "device-token-1"));
        verify(postRequestedFor(urlEqualTo("/v1/projects/my-proj/messages:send"))
                .withHeader("Authorization", equalTo("Bearer ya29.token"))
                .withRequestBody(matchingJsonPath("$.message.token", equalTo("device-token-1")))
                .withRequestBody(matchingJsonPath("$.message.notification.title", equalTo("Hello")))
                .withRequestBody(matchingJsonPath("$.message.notification.body", equalTo("Body & more"))));
    }

    @Test
    void fcmOmitsBlankTitleAndFailsOn401(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/projects/p/messages:send").willReturn(aResponse().withStatus(401).withBody("expired")));
        var fcm = new FcmPushSender(props(NO_EMAIL, NO_SMS, new ProviderProperties.Push("fcm",
                new ProviderProperties.Fcm("p", "t", wm.getHttpBaseUrl()))), http);
        DeliveryEvent noSubject = new DeliveryEvent("n", "acme", Channel.PUSH, Priority.NORMAL, "tok", null, "b", 0, Instant.now(), null);
        assertThatThrownBy(() -> fcm.send(noSubject)).isInstanceOfSatisfying(ProviderException.class,
                e -> assertThat(e.getStatus()).isEqualTo(401));
        verify(postRequestedFor(urlEqualTo("/v1/projects/p/messages:send"))
                .withRequestBody(notMatching(".*title.*")));
    }

    // ---- SES ----

    private SesEmailSender ses(WireMockRuntimeInfo wm) {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        return new SesEmailSender(props(new ProviderProperties.Email("ses",
                new ProviderProperties.Ses("eu-west-1", "AKIDEXAMPLE", "secret", "from@example.com", wm.getHttpBaseUrl()),
                new ProviderProperties.Smtp(null)), NO_SMS, NO_PUSH), http, fixed);
    }

    @Test
    void sesPostsSigV4SignedSendEmail(WireMockRuntimeInfo wm) {
        stubFor(post("/v2/email/outbound-emails").willReturn(okJson("{\"MessageId\":\"x\"}")));
        ses(wm).send(event(Channel.EMAIL, "to@example.com"));
        verify(postRequestedFor(urlEqualTo("/v2/email/outbound-emails"))
                .withHeader("X-Amz-Date", equalTo("20260102T030405Z"))
                .withHeader("Authorization", matching(
                        "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260102/eu-west-1/ses/aws4_request, "
                                + "SignedHeaders=content-type;host;x-amz-date, Signature=[0-9a-f]{64}"))
                .withRequestBody(matchingJsonPath("$.FromEmailAddress", equalTo("from@example.com")))
                .withRequestBody(matchingJsonPath("$.Destination.ToAddresses[0]", equalTo("to@example.com")))
                .withRequestBody(matchingJsonPath("$.Content.Simple.Subject.Data", equalTo("Hello")))
                .withRequestBody(matchingJsonPath("$.Content.Simple.Body.Text.Data", equalTo("Body & more"))));
    }

    @Test
    void sesSignatureIsDeterministicAndDependsOnSecretAndBody(WireMockRuntimeInfo wm) {
        SesEmailSender a = ses(wm);
        String s1 = a.authorization("h", "application/json", "20260102T030405Z", "20260102", "{}");
        assertThat(a.authorization("h", "application/json", "20260102T030405Z", "20260102", "{}")).isEqualTo(s1);
        assertThat(a.authorization("h", "application/json", "20260102T030405Z", "20260102", "{ }")).isNotEqualTo(s1);
        assertThat(a.authorization("h2", "application/json", "20260102T030405Z", "20260102", "{}")).isNotEqualTo(s1);
        SesEmailSender other = new SesEmailSender(props(new ProviderProperties.Email("ses",
                new ProviderProperties.Ses("eu-west-1", "AKIDEXAMPLE", "different", "f@example.com", wm.getHttpBaseUrl()),
                new ProviderProperties.Smtp(null)), NO_SMS, NO_PUSH), http);
        assertThat(other.authorization("h", "application/json", "20260102T030405Z", "20260102", "{}")).isNotEqualTo(s1);
    }

    @Test
    void sesThrottlingIsRetryable(WireMockRuntimeInfo wm) {
        stubFor(post("/v2/email/outbound-emails").willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> ses(wm).send(event(Channel.EMAIL, "to@example.com")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void connectionFailureBecomesProviderException() {
        var broken = new TwilioSmsSender(props(NO_EMAIL, new ProviderProperties.Sms("twilio",
                new ProviderProperties.Twilio("AC1", "t", "+1", "http://127.0.0.1:1")), NO_PUSH), http);
        assertThatThrownBy(() -> broken.send(event(Channel.SMS, "+2")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.getStatus()).isEqualTo(-1));
    }
}
