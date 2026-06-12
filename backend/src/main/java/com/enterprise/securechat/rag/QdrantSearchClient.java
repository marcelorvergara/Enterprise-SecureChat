package com.enterprise.securechat.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class QdrantSearchClient {

    private final RestClient restClient;
    private final String collection;

    public QdrantSearchClient(
            @Qualifier("qdrantRestClient") RestClient restClient,
            @Value("${qdrant.collection}") String collection) {
        this.restClient = restClient;
        this.collection = collection;
    }

    /**
     * Performs a vector similarity search against Qdrant with an optional FGA filter.
     * The filter is the map produced by {@code FgaService.buildQdrantFilter()} —
     * an empty map means no restrictions (all documents visible).
     */
    public List<SearchHit> search(List<Float> vector, Map<String, Object> filter, int limit) {
        var request = new SearchRequest(
                vector,
                limit,
                true,
                filter.isEmpty() ? null : filter
        );
        var response = restClient.post()
                .uri("/collections/{col}/points/search", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(SearchResponse.class);

        return (response == null || response.result() == null) ? List.of() : response.result();
    }

    /**
     * Fetches a single Qdrant point by ID and returns it as a SearchHit (score is 0 for direct lookups).
     * Returns null if the point does not exist.
     */
    public SearchHit getPoint(String pointId) {
        var response = restClient.get()
                .uri("/collections/{col}/points/{id}", collection, pointId)
                .retrieve()
                .body(PointResponse.class);
        return (response == null || response.result() == null) ? null : response.result();
    }

    record SearchRequest(
            List<Float> vector,
            int limit,
            @JsonProperty("with_payload") boolean withPayload,
            Object filter
    ) {}

    record SearchResponse(List<SearchHit> result) {}

    record PointResponse(SearchHit result) {}

    public record SearchHit(String id, float score, Map<String, Object> payload) {}
}
