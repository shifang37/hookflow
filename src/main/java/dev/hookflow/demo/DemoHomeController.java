package dev.hookflow.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Sends the app root to the console.
 *
 * <p>Separate from {@link DemoController} because that one is mapped under
 * {@code /v1/demo}: a {@code "/"} mapping inside it would become
 * {@code /v1/demo/"} instead of the site root.
 */
@RestController
@Profile("all")
@ConditionalOnProperty(prefix = "hookflow.demo", name = "enabled", havingValue = "true")
public class DemoHomeController {

    @GetMapping("/")
    public RedirectView root() {
        return new RedirectView("/demo/index.html");
    }
}
