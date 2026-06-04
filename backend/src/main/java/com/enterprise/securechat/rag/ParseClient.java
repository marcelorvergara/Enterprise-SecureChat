package com.enterprise.securechat.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Component
public class ParseClient {

    private final RestClient restClient;

    public ParseClient(@Qualifier("parseRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public String parse(MultipartFile file) throws IOException {
        var bytes = file.getBytes();
        var filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        var resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() { return filename; }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", resource);
        var response = restClient.post()
                .uri("/parse")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(ParseResponse.class);
        return response != null ? response.text() : "";
    }

    record ParseResponse(String text, String filename) {}
}
