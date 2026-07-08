package com.enterprise.securechat.config;

import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Google's Firestore client resolves credentials lazily, at the first RPC — not at
 * construction — so firestoreClient() succeeds and returns a non-null client even when ADC is
 * completely unresolvable (verified by running this test with GOOGLE_APPLICATION_CREDENTIALS
 * pointed at a nonexistent file). The real safety net for bad/missing credentials is therefore
 * the try/catch around the actual write in LlmTelemetryService.record() — see
 * LlmTelemetryServiceTest.record_swallowsFirestoreFailuresInsteadOfThrowing — not this bean.
 * What setProjectId(...) here does guard against is a different, genuinely eager failure:
 * FirestoreOptions.getDefaultInstance()'s ambient project-ID resolution (env var or metadata
 * server), which is unavailable in local docker-compose dev and would otherwise throw before
 * credentials are ever consulted.
 */
class FirestoreConfigTest {

    @Test
    void firestoreClient_neverThrowsAtConstruction_regardlessOfCredentialValidity() {
        var config = new FirestoreConfig();

        Firestore result = assertDoesNotThrow(() -> config.firestoreClient("enp-securechat"));

        assertNotNull(result, "client construction with an explicit project id should always succeed");
    }
}
