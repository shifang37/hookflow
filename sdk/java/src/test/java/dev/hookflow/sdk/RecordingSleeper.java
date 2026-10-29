package dev.hookflow.sdk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Records retry delays instead of waiting, so retry tests finish instantly and can
 * assert the exact backoff sequence.
 */
final class RecordingSleeper implements Sleeper {

    private final List<Duration> delays = new ArrayList<>();

    @Override
    public void sleep(Duration duration) {
        delays.add(duration);
    }

    List<Duration> delays() {
        return delays;
    }
}
