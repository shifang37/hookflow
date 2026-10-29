package dev.hookflow.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the built console as a single-page app.
 *
 * <p>The bundle in {@code classpath:static/ui/} is reached through Spring's static
 * resource handler, which answers {@code GET /ui/assets/index-<hash>.js} correctly but
 * has no idea that {@code /ui/events} is a client-side route: a refresh there would be
 * a 404. This forwards those route-shaped requests to {@code index.html} and lets the
 * router take over.
 *
 * <p><b>Why the pattern excludes dots.</b> {@code /ui/{route:[^\.]*\}} matches
 * {@code /ui/events} but not {@code /ui/assets/index-abc.js}, so a missing asset stays a
 * 404 instead of being answered with an HTML page. That distinction matters: a missing
 * chunk served as HTML fails with a MIME type error that points at the wrong thing.
 *
 * <p>The alternative is {@code HashRouter} in the frontend, which needs no backend
 * change but puts a {@code #} in every URL. Ten lines here are worth keeping the URLs
 * clean, and the route set is deliberately flat (single segment) so the pattern stays
 * this simple.
 *
 * <p>Profile-gated like every other web layer: in a {@code relay}- or {@code worker}-only
 * deployment there is no UI to serve.
 */
@Controller
@Profile({"api", "all"})
public class UiForwardController {

    private static final String INDEX = "forward:/ui/index.html";

    @GetMapping({"/ui", "/ui/", "/ui/{route:[^\\.]*}"})
    public String forwardClientRoute() {
        return INDEX;
    }
}
