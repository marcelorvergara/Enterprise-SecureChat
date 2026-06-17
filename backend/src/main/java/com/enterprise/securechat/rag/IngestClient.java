package com.enterprise.securechat.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Objects;

@Component
public class IngestClient {

    private final RestClient restClient;

    public IngestClient(@Qualifier("ingestRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public IngestResult ingest(MultipartFile file, String buPath) throws IOException {
        var filename = Objects.requireNonNullElse(file.getOriginalFilename(), "upload");
        var map = new LinkedMultiValueMap<String, Object>();
        map.add("file", new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() { return filename; }
        });
        map.add("bu_path", buPath);

        var response = restClient.post()
                .uri("/ingest")
                .contentType(Objects.requireNonNull(MediaType.MULTIPART_FORM_DATA))
                .body(map)
                .retrieve()
                .body(IngestResponse.class);

        if (response == null) {
            return new IngestResult("error", 0, buPath);
        }
        return new IngestResult(response.status(), response.chunks(), response.path());
    }

    record IngestResponse(
            String status,
            int chunks,
            String path
    ) {}

    public record IngestResult(String status, int chunks, String path) {}
}
