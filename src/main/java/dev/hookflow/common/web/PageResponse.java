package dev.hookflow.common.web;

import java.util.List;

/** Offset pagination envelope. Sorted/keyset pagination is deferred to v0.3. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total);
    }
}
