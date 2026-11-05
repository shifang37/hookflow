package dev.hookflow.common.web;

import java.util.List;

/** Offset pagination envelope. Keyset pagination is deferred; operator lists are small. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total);
    }
}
