package dev.hookflow.tools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * An <b>open-loop</b> load generator, written because the honest number matters
 * more than the convenient tool.
 *
 * <pre>
 *   java -cp target/classes dev.hookflow.tools.LoadGen \
 *     --url http://localhost:8080/v1/events --api-key hk_xxx \
 *     --rate 200 --duration 30 --warmup 5 --threads 4 --payload-bytes 2048
 * </pre>
 *
 * <h2>Why not ab / k6 / JMeter</h2>
 *
 * <p>Those are <em>closed loop</em>: each virtual user sends a request, waits for
 * the response, then sends the next one. When the system slows down, the load
 * generator slows down with it — so the requests that would have queued during the
 * slow period are <b>never sent</b>. The measured latency therefore excludes
 * exactly the delay that dominates under overload. This is called
 * <b>coordinated omission</b> (Gil Tene), and it is why a closed-loop p99 of 80 ms
 * can coexist with users waiting four seconds.
 *
 * <p>This tool sends at a fixed <em>rate</em>: request {@code i} is due at
 * {@code start + i / rate} no matter how long request {@code i-1} took. If the
 * system falls behind, the due time has already passed and the measured latency
 * grows — which is what a real traffic source does.
 *
 * <p>To make the difference visible instead of merely asserted, both numbers are
 * reported for the same run:
 *
 * <ul>
 *   <li><b>open loop</b> — {@code completion - dueTime}. Includes the time spent
 *       waiting because the platform was busy. The number that describes the user
 *       experience.</li>
 *   <li><b>closed loop</b> — {@code completion - sendTime}. What a closed-loop
 *       tool would print for the very same run. Always smaller, and the gap is the
 *       coordinated omission it is guilty of.</li>
 * </ul>
 *
 * <p>Output is plain text on stdout plus an optional per-second CSV, so a report
 * can quote raw output instead of a summary somebody typed by hand.
 */
public final class LoadGen {

    private LoadGen() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String url = options.getOrDefault("url", "http://localhost:8080/v1/events");
        String apiKey = options.get("api-key");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("--api-key is required");
            System.exit(2);
            return;
        }
        int rate = Integer.parseInt(options.getOrDefault("rate", "100"));
        int durationSeconds = Integer.parseInt(options.getOrDefault("duration", "30"));
        int warmupSeconds = Integer.parseInt(options.getOrDefault("warmup", "5"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "4"));
        int payloadBytes = Integer.parseInt(options.getOrDefault("payload-bytes", "2048"));
        int batchSize = Math.max(1, Integer.parseInt(options.getOrDefault("batch-size", "1")));
        String timelinePath = options.get("timeline");
        String tag = options.getOrDefault("tag", "run");

        // --rate is always events per second, in both modes: that is the number the
        // platform is sized in, and it keeps the two runs of an A/B comparable. A
        // batch request therefore represents batchSize events and is due every
        // batchSize/rate seconds.
        long totalEvents = (long) rate * (durationSeconds + warmupSeconds);
        long totalRequests = batchSize == 1
                ? totalEvents
                : Math.max(1, totalEvents / batchSize);
        long warmupNanos = warmupSeconds * 1_000_000_000L;
        long runId = System.nanoTime();

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        Samples openLoop = new Samples(totalRequests);
        Samples closedLoop = new Samples(totalRequests);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicLong nextIndex = new AtomicLong();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        CountDownLatch started = new CountDownLatch(1);
        long warmupStart = System.nanoTime();

        List<TimelineBucket> buckets = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    started.await();
                    while (true) {
                        long index = nextIndex.getAndIncrement();
                        if (index >= totalRequests) {
                            return;
                        }
                        long dueNanos = warmupStart + (index * batchSize * 1_000_000_000L) / rate;
                        parkUntil(dueNanos);

                        int current = inFlight.incrementAndGet();
                        peakInFlight.accumulateAndGet(current, Math::max);
                        long sendStart = System.nanoTime();
                        byte[] payload = batchSize == 1
                                ? eventPayload(payloadBytes)
                                : batchPayload(batchSize, payloadBytes, runId, index);
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(60))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
                        if (batchSize == 1) {
                            // The single-event endpoint takes the key in a header; batch
                            // items carry their own key inside the body.
                            builder.header("Idempotency-Key", "loadgen-" + runId + "-" + index);
                        }
                        HttpRequest request = builder.build();
                        try {
                            HttpResponse<Void> response = client.send(request,
                                    HttpResponse.BodyHandlers.discarding());
                            long completed = System.nanoTime();
                            boolean eligible = completed - warmupStart > warmupNanos;
                            if (eligible) {
                                // The two views of the same event. Samples are kept in
                                // microseconds; System.nanoTime is nanoseconds.
                                openLoop.add((completed - dueNanos) / 1_000L);
                                closedLoop.add((completed - sendStart) / 1_000L);
                                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                                    successes.addAndGet(batchSize);
                                } else {
                                    failures.addAndGet(batchSize);
                                }
                                buckets.add(new TimelineBucket(
                                        (int) ((completed - warmupStart - warmupNanos) / 1_000_000_000L),
                                        response.statusCode()));
                            }
                        } catch (IOException e) {
                            long completed = System.nanoTime();
                            if (completed - warmupStart > warmupNanos) {
                                openLoop.add((completed - dueNanos) / 1_000L);
                                closedLoop.add((completed - sendStart) / 1_000L);
                                failures.addAndGet(batchSize);
                                buckets.add(new TimelineBucket(
                                        (int) ((completed - warmupStart - warmupNanos) / 1_000_000_000L),
                                        -1));
                            }
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "loadgen-" + t);
            worker.setDaemon(true);
            worker.start();
        }

        started.countDown();
        done.await();
        long elapsedNanos = System.nanoTime() - warmupStart - warmupNanos;

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        int total = successes.get() + failures.get();

        System.out.println("=== hookflow loadgen ===");
        System.out.println("tag               " + tag);
        System.out.println("target            " + url);
        System.out.println("model             open loop, fixed rate"
                + (batchSize > 1 ? ", batch submit" : ""));
        System.out.println("rate              " + rate + " events/s"
                + (batchSize > 1
                        ? "  (" + String.format(Locale.ROOT, "%.2f", rate / (double) batchSize) + " req/s)"
                        : ""));
        System.out.println("batch size        " + batchSize + " event(s) per request");
        System.out.println("duration          " + durationSeconds + "s (after " + warmupSeconds + "s warmup)");
        System.out.println("senders           " + threads + " threads, peak in flight " + peakInFlight.get());
        System.out.println("payload           " + payloadBytes + " bytes per event");
        System.out.println("requests          " + total);
        System.out.println("succeeded         " + successes.get()
                + " events (" + percent(successes.get(), total) + ")");
        System.out.println("failed            " + failures.get()
                + " events (" + percent(failures.get(), total) + ")");
        System.out.println("throughput        " + String.format(Locale.ROOT, "%.1f", total / elapsedSeconds)
                + " events/s");
        String perRequest = batchSize > 1 ? " (per request, " + batchSize + " events each)" : "";
        printSamples("latency" + perRequest + ", open loop (coordinated-omission corrected)", openLoop);
        printSamples("latency" + perRequest + ", closed loop (what a naive tool reports)", closedLoop);

        if (!openLoop.isEmpty() && !closedLoop.isEmpty()) {
            long openP99 = openLoop.percentile(99.0);
            long closedP99 = closedLoop.percentile(99.0);
            System.out.println("p99 inflation     x" + String.format(Locale.ROOT, "%.1f",
                    openP99 / (double) Math.max(1, closedP99))
                    + "  (how much a closed-loop tool under-reports at p99)");
        }

        if (timelinePath != null && !timelinePath.isBlank()) {
            writeTimeline(timelinePath, buckets, batchSize);
            System.out.println("timeline          " + timelinePath);
        }
        System.out.println("=== end ===");
    }

    private static void printSamples(String label, Samples samples) {
        System.out.println(label);
        if (samples.isEmpty()) {
            System.out.println("  (no samples)");
            return;
        }
        for (double p : new double[]{50, 90, 95, 99, 99.9, 99.99}) {
            System.out.println("  p" + p + "  " + millis(samples.percentile(p)));
        }
        System.out.println("  max   " + millis(samples.percentile(100.0)));
    }

    private static String millis(long micros) {
        return String.format(Locale.ROOT, "%8.2f ms", micros / 1000.0);
    }

    private static String percent(long part, long total) {
        if (total == 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f%%", 100.0 * part / total);
    }

    private static void writeTimeline(String path, List<TimelineBucket> buckets, int batchSize)
            throws IOException {
        Map<Integer, int[]> bySecond = new LinkedHashMap<>();
        synchronized (buckets) {
            for (TimelineBucket bucket : buckets) {
                int[] counts = bySecond.computeIfAbsent(bucket.second(), key -> new int[3]);
                if (bucket.status() >= 200 && bucket.status() < 300) {
                    counts[0] += batchSize;
                } else if (bucket.status() == 429) {
                    counts[1] += batchSize;
                } else {
                    counts[2] += batchSize;
                }
            }
        }
        StringBuilder sb = new StringBuilder("second,reached_rate,accepted,throttled_429,other_errors\n");
        bySecond.forEach((second, counts) -> sb.append(second).append(',')
                .append(counts[0] + counts[1] + counts[2]).append(',')
                .append(counts[0]).append(',')
                .append(counts[1]).append(',')
                .append(counts[2]).append('\n'));
        java.nio.file.Files.writeString(java.nio.file.Path.of(path), sb.toString(), StandardCharsets.UTF_8);
    }

    private record TimelineBucket(int second, int status) {
    }

    private static void parkUntil(long dueNanos) {
        while (true) {
            long remaining = dueNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            LockSupport.parkNanos(remaining);
        }
    }

    /**
     * A single-event JSON body of approximately {@code bytes} bytes.
     *
     * <p>The filler is incompressible on purpose: a payload of repeated characters
     * would make any transport compression look like a capacity gain that does not
     * exist for real traffic.
     */
    private static byte[] eventPayload(int bytes) {
        StringBuilder sb = new StringBuilder(bytes + 64);
        sb.append("{\"eventType\":\"load.gen\",\"payload\":{\"data\":\"");
        appendFiller(sb, Math.max(16, bytes - 64));
        sb.append("\"}}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A batch body carrying {@code batchSize} events of roughly {@code bytesPerEvent}
     * each, with a distinct idempotency key per event so a retried run can never
     * collapse into the previous run's events.
     */
    private static byte[] batchPayload(int batchSize, int bytesPerEvent, long runId, long requestIndex) {
        int filler = Math.max(16, bytesPerEvent - 96);
        StringBuilder sb = new StringBuilder(batchSize * (bytesPerEvent + 64) + 16);
        sb.append("{\"events\":[");
        for (int i = 0; i < batchSize; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"eventType\":\"load.gen\",\"payload\":{\"data\":\"");
            appendFiller(sb, filler);
            sb.append("\"},\"idempotencyKey\":\"loadgen-")
                    .append(runId).append('-').append(requestIndex).append('-').append(i)
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendFiller(StringBuilder sb, int length) {
        java.util.Random random = new java.util.Random(42);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            if (key.contains("=")) {
                int split = key.indexOf('=');
                options.put(key.substring(0, split), key.substring(split + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    /** Preallocated latency samples; percentiles come from a copy that gets sorted once. */
    private static final class Samples {

        private final AtomicInteger size = new AtomicInteger();
        private final long[] values;

        Samples(long capacity) {
            this.values = new long[(int) Math.min(Integer.MAX_VALUE - 8, capacity)];
        }

        void add(long micros) {
            int index = size.getAndIncrement();
            if (index < values.length) {
                values[index] = Math.max(0, micros);
            }
        }

        boolean isEmpty() {
            return size.get() == 0;
        }

        long percentile(double percentile) {
            int count = Math.min(size.get(), values.length);
            if (count == 0) {
                return 0;
            }
            long[] sorted = Arrays.copyOf(values, count);
            Arrays.sort(sorted);
            int index = (int) Math.ceil(percentile / 100.0 * count) - 1;
            return sorted[Math.min(count - 1, Math.max(0, index))];
        }
    }
}
