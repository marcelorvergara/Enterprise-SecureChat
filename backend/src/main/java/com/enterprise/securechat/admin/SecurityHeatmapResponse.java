package com.enterprise.securechat.admin;

import java.util.List;

public record SecurityHeatmapResponse(
        List<PathHitCount> topRestrictedPaths,
        List<DlpDensityPoint> dlpDensityByDay
) {
    public record PathHitCount(String path, long hitCount) {}
    public record DlpDensityPoint(String day, long totalRedacted) {}
}
