package dev.hookflow.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The two halves of the SPA fallback that matter: client routes resolve to the shell,
 * and asset paths are left alone.
 *
 * <p>{@code standaloneSetup} on purpose — this is a routing rule, not an integration
 * behaviour, and proving it needs neither a database nor a broker.
 */
class UiForwardControllerTest {

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new UiForwardController()).build();

    @Test
    @DisplayName("every top-level client route is served the SPA shell")
    void clientRoutesForwardToIndex() throws Exception {
        for (String route : new String[] {"/ui", "/ui/", "/ui/events", "/ui/deliveries",
                "/ui/tenants", "/ui/monitor"}) {
            mockMvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(view().name("forward:/ui/index.html"));
        }
    }

    @Test
    @DisplayName("a missing asset stays a 404 instead of being answered with HTML")
    void assetsAreNotSwallowed() throws Exception {
        mockMvc.perform(get("/ui/assets/index-CoYFhpFV.js"))
                .andExpect(status().isNotFound());
    }
}
