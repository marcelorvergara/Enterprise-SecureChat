package com.enterprise.securechat.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LlmTelemetryServiceTest {

    @Mock private LlmTelemetryRepository repository;

    @Test
    void record_savesRowWithGivenFields() {
        var service = new LlmTelemetryService(repository);

        service.record("/api/chat", "claude-sonnet-4-6", 1234L, 100, 50, 0.0021, true, null);

        verify(repository).save(any(LlmTelemetry.class));
    }

    @Test
    void record_swallowsRepositoryFailuresInsteadOfThrowing() {
        var service = new LlmTelemetryService(repository);
        doThrow(new RuntimeException("Neon connection reset")).when(repository).save(any());

        service.record("/api/chat/stream", "claude-sonnet-4-6", 500L, 10, 5, 0.0001, false, "boom");
        // No assertion beyond "did not throw" — a telemetry write failure must never
        // propagate back to the caller (the chat request thread).
    }
}
