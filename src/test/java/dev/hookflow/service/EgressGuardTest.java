package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.config.HookFlowProperties;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF defence. The default policy must refuse everything that is not on the
 * public internet, because a webhook platform is otherwise a ready-made pivot
 * into the network it runs in.
 */
class EgressGuardTest {

    private static EgressGuard strict() {
        return new EgressGuard(new HookFlowProperties.Egress(false, List.of()), EgressGuardTest::resolve);
    }

    /** Deterministic stand-in for DNS: the "hostname" is the literal address. */
    private static InetAddress[] resolve(String host) throws UnknownHostException {
        if (host.contains("public.example")) {
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        }
        if (host.equals("rebind.example")) {
            return new InetAddress[]{InetAddress.getByName("93.184.216.34"),
                    InetAddress.getByName("10.0.0.7")};
        }
        return new InetAddress[]{InetAddress.getByName(host)};
    }

    @Test
    @DisplayName("loopback is refused by default")
    void refusesLoopback() {
        assertThatThrownBy(() -> strict().validate("http://127.0.0.1:9099/hook"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("non-public");
        assertThatThrownBy(() -> strict().validate("http://localhost:9099/hook"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("cloud metadata endpoint is refused by default")
    void refusesCloudMetadata() {
        assertThatThrownBy(() -> strict().validate("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("non-public");
    }

    @Test
    @DisplayName("private ranges are refused by default")
    void refusesPrivateRanges() {
        for (String url : List.of(
                "http://10.1.2.3/hook",
                "http://172.16.5.4/hook",
                "http://192.168.1.10/hook",
                "http://100.64.0.1/hook",
                "http://0.0.0.0/hook")) {
            assertThatThrownBy(() -> strict().validate(url))
                    .as(url)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("a hostname resolving to a private address is refused even if one address is public")
    void refusesMixedResolution() {
        assertThatThrownBy(() -> strict().validate("http://rebind.example/hook"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10.0.0.7");
    }

    @Test
    @DisplayName("public targets are accepted")
    void acceptsPublicTargets() {
        assertThatCode(() -> strict().validate("https://public.example/hook")).doesNotThrowAnyException();
        assertThatCode(() -> strict().validate("http://93.184.216.34/hook")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("malformed or non-http urls are refused")
    void refusesMalformedUrls() {
        for (String url : List.of("ftp://public.example/x", "not a url", "http:///nohost",
                "http://user:pass@public.example/x", "")) {
            assertThatThrownBy(() -> strict().validate(url)).as(url).isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("the demo profile may relax the range check")
    void permissiveModeAllowsLoopback() {
        EgressGuard permissive = new EgressGuard(
                new HookFlowProperties.Egress(true, List.of()), EgressGuardTest::resolve);
        assertThatCode(() -> permissive.validate("http://127.0.0.1:9099/hook")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an explicit allow-list entry overrides the range check even in strict mode")
    void allowListOverridesStrictMode() {
        EgressGuard withAllowList = new EgressGuard(
                new HookFlowProperties.Egress(false, List.of("127.0.0.1")), EgressGuardTest::resolve);
        assertThatCode(() -> withAllowList.validate("http://127.0.0.1:9099/hook")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unresolvable host is refused instead of attempted")
    void refusesUnresolvableHost() {
        assertThatThrownBy(() -> strict().validate("http://does-not-exist.invalid/hook"))
                .isInstanceOf(ApiException.class);
    }

    /**
     * The range classification is the security-relevant part, so it is stated as a
     * property over the whole IPv4 first-octet space rather than a handful of examples.
     */
    @Property
    void ipv4RangeClassificationMatchesTheDocumentedPolicy(
            @ForAll @IntRange(min = 0, max = 255) int a,
            @ForAll @IntRange(min = 0, max = 255) int b,
            @ForAll @IntRange(min = 0, max = 255) int c,
            @ForAll @IntRange(min = 0, max = 255) int d) throws UnknownHostException {

        String literal = a + "." + b + "." + c + "." + d;
        InetAddress address = InetAddress.getByName(literal);
        if (!(address instanceof Inet4Address)) {
            return;
        }
        boolean expected = a == 0                                   // 0.0.0.0/8
                || a == 127                                          // loopback
                || a == 10                                           // private
                || (a == 172 && b >= 16 && b <= 31)                  // private
                || (a == 192 && b == 168)                            // private
                || (a == 169 && b == 254)                            // link-local + metadata
                || (a == 100 && b >= 64 && b <= 127)                 // CGNAT
                || (a >= 224);                                       // multicast + reserved

        assertThat(EgressGuard.isInternal(address)).as(literal).isEqualTo(expected);
    }
}
