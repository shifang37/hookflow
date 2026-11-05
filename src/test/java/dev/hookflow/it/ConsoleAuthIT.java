package dev.hookflow.it;

import dev.hookflow.config.HookFlowProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console login boundary, exercised through the real servlet filter chain.
 *
 * <p>Unit tests can prove a password encoder and a login handler; they cannot prove
 * that {@code /ui/**} redirects, that a session cookie survives the next request,
 * that a viewer is stopped on a write, or that the hand-rolled API-key filter still
 * owns {@code /v1/events}. Those are properties of the assembled chain, so this IT
 * drives it over HTTP with a cookie jar and no test-only security shortcuts.
 *
 * <p>The bootstrap-token cases are the compatibility contract: scripts and the Helm
 * smoke test must keep working without a browser, a session or a CSRF token.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pin the test accounts so a developer's shell environment or CI secret
        // cannot change which password this test logs in with.
        properties = {
                "hookflow.console.users[0].username=admin",
                "hookflow.console.users[0].password=admin123",
                "hookflow.console.users[0].roles[0]=ADMIN",
                "hookflow.console.users[1].username=viewer",
                "hookflow.console.users[1].password=viewer123",
                "hookflow.console.users[1].roles[0]=VIEWER"
        })
class ConsoleAuthIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private HookFlowProperties properties;

    @Test
    @DisplayName("an anonymous browser is redirected to the login page, but assets load")
    void anonymousConsoleRedirectsToLogin() throws Exception {
        Browser browser = new Browser();

        HttpResponse<String> page = browser.get("/ui/events");
        assertThat(page.statusCode()).isEqualTo(302);
        assertThat(page.headers().firstValue("Location").orElse("")).contains("/ui/login");

        HttpResponse<String> login = browser.get("/ui/login");
        assertThat(login.statusCode())
                .as("login headers: %s body: %s", login.headers(), login.body())
                .isEqualTo(200);
        // A missing chunk must stay a 404. Redirecting it to the login page would
        // surface as a MIME error pointing at the wrong file.
        assertThat(browser.get("/ui/assets/does-not-exist.js").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the session probe is a 200 for anonymous callers and materialises the CSRF cookie")
    void sessionProbeWorksBeforeLogin() throws Exception {
        Browser browser = new Browser();

        HttpResponse<String> response = browser.get("/api/auth/session");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("authenticated").asBoolean()).isFalse();
        assertThat(body.get("username").isNull()).isTrue();
        assertThat(body.get("roles").isEmpty()).isTrue();
        assertThat(browser.csrfCookie()).isNotBlank();
    }

    @Test
    @DisplayName("an admin logs in, gets a session and can read the operator API")
    void adminLoginReadsOperatorApi() throws Exception {
        Browser browser = new Browser();

        assertThat(browser.login("admin", "admin123").statusCode()).isEqualTo(200);
        JsonNode identity = json(browser.get("/api/auth/session"));
        assertThat(identity.get("authenticated").asBoolean()).isTrue();
        assertThat(identity.get("username").asString()).isEqualTo("admin");
        assertThat(identity.get("roles").toString()).contains("ADMIN");

        HttpResponse<String> tenants = browser.get("/v1/admin/tenants");
        assertThat(tenants.statusCode()).isEqualTo(200);
        assertThat(json(tenants).get("items").isArray()).isTrue();
    }

    @Test
    @DisplayName("a viewer can read the operator API but cannot create or update anything")
    void viewerIsReadOnly() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.login("viewer", "viewer123").statusCode()).isEqualTo(200);

        assertThat(browser.get("/v1/admin/overview").statusCode()).isEqualTo(200);

        HttpResponse<String> create = browser.postJson("/v1/admin/tenants",
                "{\"name\":\"viewer-" + UUID.randomUUID() + "\",\"rateLimit\":10}");
        assertThat(create.statusCode()).isEqualTo(403);
        assertThat(json(create).get("code").asInt()).isEqualTo(40300);

        HttpResponse<String> update = browser.patchJson("/v1/admin/tenants/1", "{\"weight\":2}");
        assertThat(update.statusCode()).isEqualTo(403);
        assertThat(json(update).get("message").asString()).contains("role");
    }

    @Test
    @DisplayName("a session write without the CSRF header is rejected even for an admin")
    void sessionWritesNeedCsrf() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.login("admin", "admin123").statusCode()).isEqualTo(200);
        String csrf = browser.csrf();

        HttpResponse<String> withoutToken = browser.postJsonNoCsrf("/v1/admin/tenants",
                "{\"name\":\"no-csrf-" + UUID.randomUUID() + "\",\"rateLimit\":10}");
        assertThat(withoutToken.statusCode()).isEqualTo(403);
        assertThat(json(withoutToken).get("message").asString()).contains("CSRF");

        HttpResponse<String> withToken = browser.postJson("/v1/admin/tenants",
                "{\"name\":\"with-csrf-" + UUID.randomUUID() + "\",\"rateLimit\":10}");
        assertThat(withToken.statusCode()).isEqualTo(201);
        assertThat(json(withToken).get("apiKey").asString()).startsWith("hk_");
        assertThat(csrf).isNotBlank();
    }

    @Test
    @DisplayName("the bootstrap token still authenticates scripts, with no session or CSRF token")
    void bootstrapTokenRemainsACompatibleMachineCredential() throws Exception {
        Browser script = new Browser();

        HttpResponse<String> read = script.getWithBootstrapToken("/v1/admin/tenants");
        assertThat(read.statusCode()).isEqualTo(200);

        HttpResponse<String> create = script.postJsonWithBootstrapToken("/v1/admin/tenants",
                "{\"name\":\"script-" + UUID.randomUUID() + "\",\"rateLimit\":10}");
        assertThat(create.statusCode()).isEqualTo(201);

        assertThat(script.getWithBootstrapToken("/v1/admin/overview", "wrong").statusCode())
                .isEqualTo(403);
        assertThat(script.get("/v1/admin/overview").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("logout invalidates the session, and the operator API answers 401 instead of redirecting")
    void logoutInvalidatesTheSession() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.login("admin", "admin123").statusCode()).isEqualTo(200);
        String csrf = browser.csrf();

        HttpResponse<String> logout = browser.postForm("/api/auth/logout", "", csrf);
        assertThat(logout.statusCode()).isEqualTo(204);

        HttpResponse<String> afterLogout = browser.get("/v1/admin/tenants");
        assertThat(afterLogout.statusCode()).isEqualTo(401);
        assertThat(json(afterLogout).get("code").asInt()).isEqualTo(40100);
        assertThat(browser.get("/api/auth/session").body()).contains("\"authenticated\":false");
    }

    @Test
    @DisplayName("a bad password is a JSON 401, not a redirect to the login page")
    void badPasswordIsJsonUnauthorized() throws Exception {
        Browser browser = new Browser();

        HttpResponse<String> response = browser.login("admin", "wrong-password");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).get("code").asInt()).isEqualTo(40100);
        assertThat(response.body()).doesNotContain("wrong-password");
    }

    @Test
    @DisplayName("the public API still belongs to the hand-rolled API-key filter")
    void publicApiStillUsesApiKeyAuth() throws Exception {
        Browser browser = new Browser();

        HttpResponse<String> response = browser.get("/v1/events");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).get("error").asString()).isEqualTo("UNAUTHORIZED");
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    /** One browser tab: its own cookies, no automatic redirects. */
    private final class Browser {

        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return send(request(path).GET().build());
        }

        HttpResponse<String> getWithBootstrapToken(String path) throws IOException, InterruptedException {
            return getWithBootstrapToken(path, bootstrapToken());
        }

        HttpResponse<String> getWithBootstrapToken(String path, String token)
                throws IOException, InterruptedException {
            return send(request(path).header("X-Bootstrap-Token", token).GET().build());
        }

        HttpResponse<String> login(String username, String password) throws IOException, InterruptedException {
            // A fresh session probe gives the POST a CSRF cookie; the real SPA does
            // the same on mount before rendering the form.
            String csrf = csrf();
            String form = "username=" + url(username) + "&password=" + url(password);
            return postForm("/api/auth/login", form, csrf);
        }

        HttpResponse<String> postForm(String path, String form, String csrf)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = request(path)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
            if (csrf != null) {
                builder.header("X-XSRF-TOKEN", csrf);
            }
            return send(builder.build());
        }

        HttpResponse<String> postJson(String path, String body) throws IOException, InterruptedException {
            String csrf = csrf();
            return postJson(path, body, csrf);
        }

        HttpResponse<String> postJsonWithBootstrapToken(String path, String body)
                throws IOException, InterruptedException {
            return send(request(path)
                    .header("X-Bootstrap-Token", bootstrapToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }

        HttpResponse<String> postJsonNoCsrf(String path, String body) throws IOException, InterruptedException {
            return send(request(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }

        HttpResponse<String> patchJson(String path, String body) throws IOException, InterruptedException {
            return send(request(path)
                    .header("X-XSRF-TOKEN", csrf())
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build());
        }

        /** Forces a session probe, which is what writes a fresh cookie after login/logout. */
        String csrf() throws IOException, InterruptedException {
            get("/api/auth/session");
            return csrfCookie();
        }

        String csrfCookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                    .map(java.net.HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
        }

        private HttpResponse<String> postJson(String path, String body, String csrf)
                throws IOException, InterruptedException {
            return send(request(path)
                    .header("X-XSRF-TOKEN", csrf)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }

        private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10));
        }

        private String bootstrapToken() {
            return properties.admin().bootstrapToken();
        }

        private String url(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
