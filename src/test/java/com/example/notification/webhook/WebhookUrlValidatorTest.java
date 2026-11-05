package com.example.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlValidatorTest {

    /** Resolver that never touches DNS: literals are parsed, every name maps to a public address. */
    static final WebhookUrlValidator.Resolver FAKE = host -> {
        if (host.equals("unresolvable.example")) throw new UnknownHostException(host);
        if (host.equals("rebind.example")) return new InetAddress[] {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")};
        if (host.matches("[0-9.]+") || host.contains(":")) return InetAddress.getAllByName(host);
        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
    };

    WebhookUrlValidator validator = new WebhookUrlValidator(FAKE);

    @Test
    void acceptsPublicHttps() {
        assertThat(validator.validate("https://hooks.example.com/notify?x=1").getHost()).isEqualTo("hooks.example.com");
        assertThat(validator.validate("https://hooks.example.com:8443/n").getPort()).isEqualTo(8443);
        assertThat(validator.validate("https://93.184.216.34/n")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://hooks.example.com/n",
            "ftp://hooks.example.com/n",
            "file:///etc/passwd",
            "//hooks.example.com/n",
            "hooks.example.com",
            "https://user:pw@hooks.example.com/n",
            "https:///nohost",
            "https://unresolvable.example/n",
            "https://rebind.example/n",
            "https://localhost/n",
            "https://foo.localhost/n",
            "https://db.internal/n",
            "https://printer.local/n"})
    void rejectsBadSchemesHostsAndCredentials(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(WebhookUrlValidator.InvalidWebhookUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/", "https://127.1.2.3/", "https://0.0.0.0/", "https://10.1.2.3/", "https://172.16.0.1/",
            "https://172.31.255.255/", "https://192.168.1.1/", "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/", "https://198.18.0.1/", "https://192.0.2.1/", "https://203.0.113.9/",
            "https://224.0.0.1/", "https://255.255.255.255/", "https://240.0.0.1/",
            "https://[::1]/", "https://[::]/", "https://[fe80::1]/", "https://[fc00::1]/", "https://[fd12:3456::1]/",
            "https://[2001:db8::1]/", "https://[::ffff:127.0.0.1]/", "https://[::ffff:10.0.0.1]/", "https://[ff02::1]/",
            "https://2130706433/", "https://0x7f.0.0.1/"})
    void rejectsNonPublicAddresses(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(WebhookUrlValidator.InvalidWebhookUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "8.8.8.8", "2606:4700:4700::1111"})
    void acceptsPublicNeighboursOfPrivateRanges(String ip) throws Exception {
        assertThat(WebhookUrlValidator.isBlocked(InetAddress.getByName(ip))).isFalse();
    }

    @Test
    void rejectsBlankNullAndOversized() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(WebhookUrlValidator.InvalidWebhookUrlException.class);
        assertThatThrownBy(() -> validator.validate("  ")).isInstanceOf(WebhookUrlValidator.InvalidWebhookUrlException.class);
        assertThatThrownBy(() -> validator.validate("https://a.example/" + "x".repeat(2100)))
                .isInstanceOf(WebhookUrlValidator.InvalidWebhookUrlException.class);
    }
}
