package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.config.HookFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Outbound target policy for customer webhook URLs.
 *
 * <p>Without this, any tenant can point an endpoint at {@code http://127.0.0.1:8080}
 * or {@code http://169.254.169.254/latest/meta-data/} and use this platform as an
 * SSRF pivot into the network it runs in. Every URL is therefore checked at
 * creation time: scheme, host presence, and the address ranges the host resolves to.
 *
 * <p><b>Known limitation, stated openly:</b> the check runs once at creation, and
 * the delivery path re-resolves the host. A DNS record that changes between the two
 * moments (DNS rebinding) defeats this control. Closing that gap requires pinning
 * the resolved address for the lifetime of the delivery, which is scheduled for a
 * later version because it interacts with load balancing and TLS SNI. See
 * {@code docs/operations.md}.
 */
@Component
public class EgressGuard {

    private static final Logger log = LoggerFactory.getLogger(EgressGuard.class);

    private final boolean allowPrivateTargets;
    private final List<String> allowedHosts;
    private final HostResolver resolver;

    /** Resolves a host to its addresses; injectable so tests need no real DNS. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Primary constructor used by the container. Explicitly annotated because the
     * secondary constructor exists for tests and Spring refuses to guess.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public EgressGuard(HookFlowProperties properties) {
        this(properties.security() == null || properties.security().egress() == null
                        ? new HookFlowProperties.Egress(false, List.of())
                        : properties.security().egress(),
                InetAddress::getAllByName);
    }

    /** Visible for tests. */
    public EgressGuard(HookFlowProperties.Egress egress, HostResolver resolver) {
        this.allowPrivateTargets = egress.allowPrivateTargets();
        this.allowedHosts = egress.allowedHosts() == null ? List.of() : egress.allowedHosts();
        this.resolver = resolver;
    }

    /**
     * @throws ApiException {@link dev.hookflow.common.error.ErrorCode#INVALID_REQUEST} when
     *                      the URL is malformed or points somewhere we refuse to call
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw ApiException.invalid("url is required");
        }
        if (url.length() > 2048) {
            throw ApiException.invalid("url is too long");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw ApiException.invalid("url must use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ApiException.invalid("url must contain a host");
        }
        if (uri.getUserInfo() != null) {
            throw ApiException.invalid("url must not embed credentials");
        }
        if (isExplicitlyAllowed(host)) {
            return;
        }
        if (allowPrivateTargets) {
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw ApiException.invalid("url host cannot be resolved: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw ApiException.invalid("url host cannot be resolved: " + host);
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                log.warn("Refused endpoint target {} -> {} (internal address)", host, address.getHostAddress());
                throw ApiException.invalid(
                        "url resolves to a non-public address and is refused: " + address.getHostAddress());
            }
        }
    }

    private boolean isExplicitlyAllowed(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowedHosts.stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .anyMatch(candidate -> candidate.toLowerCase(Locale.ROOT).equals(normalized));
    }

    /** Loopback, private, link-local, CGNAT, ULA, unspecified and multicast are all refused. */
    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            if (first == 0) {
                return true;                       // 0.0.0.0/8 "this network"
            }
            if (first >= 224) {
                return true;                       // 224/4 multicast, 240/4 reserved
            }
            return first == 100 && (second & 0xC0) == 64;   // 100.64.0.0/10 CGNAT
        }
        if (raw.length == 16) {
            return (raw[0] & 0xFE) == 0xFC;        // fc00::/7 unique local
        }
        return false;
    }
}
