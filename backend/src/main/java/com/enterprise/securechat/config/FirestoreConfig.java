package com.enterprise.securechat.config;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Backs the best-effort Firestore dual-write in LlmTelemetryService — see root CLAUDE.md
 * constraint #14. Project ID is set explicitly rather than relying on
 * FirestoreOptions.getDefaultInstance()'s ambient project-ID resolution (env var / metadata
 * server), which is absent in local docker-compose dev and would otherwise throw here, before
 * credentials are ever consulted.
 *
 * Note credential validity is NOT checked at this point: the Firestore client resolves ADC
 * lazily, at the first RPC, so this bean succeeds and returns a non-null client even when
 * credentials are completely unresolvable (verified in FirestoreConfigTest). The try/catch
 * here is still worth keeping as a guard against the project-ID-resolution failure mode, but
 * the write path's actual safety net for missing/bad credentials is the try/catch around the
 * RPC in LlmTelemetryService.record() — see LlmTelemetryServiceTest's Firestore-failure cases.
 */
@Configuration
public class FirestoreConfig {

    private static final Logger log = LoggerFactory.getLogger(FirestoreConfig.class);

    @Bean
    public Firestore firestoreClient(@Value("${gcp.project-id:enp-securechat}") String projectId) {
        try {
            return FirestoreOptions.newBuilder()
                    .setProjectId(projectId)
                    .build()
                    .getService();
        } catch (Exception e) {
            log.warn("Firestore client unavailable ({}); telemetry dual-write to Firestore is "
                    + "disabled for this run, Postgres writes are unaffected.", e.getMessage());
            return null;
        }
    }
}
