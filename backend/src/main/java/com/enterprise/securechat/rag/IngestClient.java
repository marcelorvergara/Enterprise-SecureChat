package com.enterprise.securechat.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
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
        var builder = new MultipartBodyBuilder();
        builder.part("file", file.getResource())
               .filename(Objects.requireNonNullElse(file.getOriginalFilename(), "upload"));
        builder.part("bu_path", buPath);

        var response = restClient.post()
                .uri("/ingest")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
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
