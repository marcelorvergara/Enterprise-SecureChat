package com.enterprise.securechat.telemetry;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmTelemetryServiceTest {

    @Mock private LlmTelemetryRepository repository;
    @Mock private Firestore firestore;
    @Mock private CollectionReference collectionReference;
    @Mock private ApiFuture<DocumentReference> apiFuture;

    @Test
    void record_savesRowWithGivenFields() {
        // No Firestore client configured (matches local/test envs without GCP ADC) —
        // the Postgres write path must be entirely unaffected.
        var service = new LlmTelemetryService(repository, null);

        service.record("/api/chat", "claude-sonnet-4-6", 1234L, 100, 50, 0.0021, true, null);

        verify(repository).save(any(LlmTelemetry.class));
    }

    @Test
    void record_swallowsRepositoryFailuresInsteadOfThrowing() {
        var service = new LlmTelemetryService(repository, null);
        doThrow(new RuntimeException("Neon connection reset")).when(repository).save(any());

        service.record("/api/chat/stream", "claude-sonnet-4-6", 500L, 10, 5, 0.0001, false, "boom");
        // No assertion beyond "did not throw" — a telemetry write failure must never
        // propagate back to the caller (the chat request thread).
    }

    @Test
    void record_writesToFirestoreWhenClientPresent() throws Exception {
        var service = new LlmTelemetryService(repository, firestore);
        when(firestore.collection("llm_telemetry")).thenReturn(collectionReference);
        when(collectionReference.add(any())).thenReturn(apiFuture);
        when(apiFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(null);

        service.record("/api/chat", "claude-sonnet-4-6", 1234L, 100, 50, 0.0021, true, null);

        verify(repository).save(any(LlmTelemetry.class));
        verify(collectionReference).add(any(Map.class));
    }

    @Test
    void record_swallowsFirestoreFailuresInsteadOfThrowing() {
        var service = new LlmTelemetryService(repository, firestore);
        when(firestore.collection("llm_telemetry")).thenThrow(new RuntimeException("Firestore unavailable"));

        service.record("/api/chat", "claude-sonnet-4-6", 1234L, 100, 50, 0.0021, true, null);

        // The Postgres write must still have gone through — a Firestore outage is invisible
        // to the primary write path.
        verify(repository).save(any(LlmTelemetry.class));
    }

    @Test
    void record_skipsFirestoreEntirelyWhenClientIsNull() {
        var service = new LlmTelemetryService(repository, null);

        service.record("/api/chat", "claude-sonnet-4-6", 1234L, 100, 50, 0.0021, true, null);

        verifyNoInteractions(firestore);
        verify(repository).save(any(LlmTelemetry.class));
        verify(collectionReference, never()).add(any());
    }
}
