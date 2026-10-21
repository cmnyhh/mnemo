package com.hs.memory.api;

import java.util.List;

public record IngestResponse(
        String extraction,
        int accepted,
        int duplicates,
        List<Result> items
) {
    public record Result(
            String id,
            String kind,
            boolean duplicate,
            boolean embedded,
            String note
    ) {
    }
}
