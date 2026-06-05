package com.enterprise.securechat.rag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestClient ingestClient;

    public DocumentController(IngestClient ingestClient) {
        this.ingestClient = ingestClient;
    }

    /**
     * Permanently indexes a BU-restricted document into Qdrant.
     *
     * The bu_path (e.g. "bu/campos/reserves") is derived server-side from the
     * caller's GROUP_BU_xxx authority — never accepted from the client. This
     * guarantees that a bu-user cannot index under another BU's path.
     */
    @PostMapping("/ingest")
    @PreAuthorize("hasAnyRole('bu-user', 'reserves-management', 'reserves-coordination')")
    public ResponseEntity<IngestClient.IngestResult> ingest(
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {

        String buPath = extractBuPath(auth);
        if (buPath == null) {
            return ResponseEntity.badRequest().build();
        }

        var result = ingestClient.ingest(file, buPath);
        return ResponseEntity.ok(result);
    }

    private String extractBuPath(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("GROUP_BU_"))
                .map(a -> "bu/" + a.substring("GROUP_BU_".length()).toLowerCase() + "/reserves")
                .findFirst()
                .orElse(null);
    }
}
