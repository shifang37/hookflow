package dev.hookflow.api;

import dev.hookflow.api.dto.Dtos.BatchReplayRequest;
import dev.hookflow.api.dto.Dtos.BatchReplayResponse;
import dev.hookflow.common.auth.ApiKeyPrincipal;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.service.DeliveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The batch replay endpoint's own contract: what it refuses before touching the database,
 * and how it reports a partial success. The replay itself (CAS, outbox, audit) is database
 * behaviour and lives in {@code BatchReplayIT}.
 */
class DeliveryControllerTest {

    private final DeliveryService deliveryService = mock(DeliveryService.class);
    private final DeliveryController controller = new DeliveryController(deliveryService);

    @AfterEach
    void clearPrincipal() {
        ApiKeyPrincipal.Context.clear();
    }

    @Test
    @DisplayName("empty, oversized, null-bearing or duplicated id lists are 400s that never reach the service")
    void rejectsMalformedBatches() {
        assertRejected(new BatchReplayRequest(null), "1-" + DeliveryService.MAX_BATCH_REPLAY);
        assertRejected(new BatchReplayRequest(List.of()), "1-" + DeliveryService.MAX_BATCH_REPLAY);
        assertRejected(new BatchReplayRequest(ids(DeliveryService.MAX_BATCH_REPLAY + 1)),
                "1-" + DeliveryService.MAX_BATCH_REPLAY);
        assertRejected(new BatchReplayRequest(Arrays.asList(1L, null)), "null");
        assertRejected(new BatchReplayRequest(List.of(1L, 2L, 1L)), "duplicates");

        verifyNoInteractions(deliveryService);
    }

    @Test
    @DisplayName("a partial batch answers 200 with one line per id and counts that add up")
    void mapsPartialResults() {
        ApiKeyPrincipal.Context.set(new ApiKeyPrincipal(7L, "it-key", "delivery:read"));
        when(deliveryService.replayBatch(7L, List.of(1L, 2L))).thenReturn(List.of(
                new DeliveryService.ReplayOutcome(1L, "PENDING", null, null),
                new DeliveryService.ReplayOutcome(2L, null, ErrorCode.ILLEGAL_TRANSITION,
                        "Only DEAD deliveries can be replayed (current: SUCCEEDED)")));

        BatchReplayResponse response = controller.replayBatch(new BatchReplayRequest(List.of(1L, 2L)));

        assertThat(response.replayed()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results()).hasSize(2);

        var replayed = response.results().get(0);
        assertThat(replayed.deliveryId()).isEqualTo(1L);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.status()).isEqualTo("PENDING");
        assertThat(replayed.code()).isNull();
        assertThat(replayed.message()).isNull();

        var rejected = response.results().get(1);
        assertThat(rejected.replayed()).isFalse();
        assertThat(rejected.status()).isNull();
        assertThat(rejected.code()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION.code());
        assertThat(rejected.message()).contains("SUCCEEDED");
    }

    private void assertRejected(BatchReplayRequest request, String messageFragment) {
        assertThatThrownBy(() -> controller.replayBatch(request))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(api.getMessage()).contains(messageFragment);
                });
    }

    private static List<Long> ids(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(Long::valueOf).toList();
    }
}
