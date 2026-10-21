package com.hs.memory.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredicateGuardTest {

    @Test
    void genericPredicatesAreExcludedFromConflictResolution() {
        assertTrue(IngestService.isGenericPredicate("使用"));
        assertTrue(IngestService.isGenericPredicate(" 是 "));
        assertTrue(IngestService.isGenericPredicate("用"));
        assertTrue(IngestService.isGenericPredicate("Uses"));
        assertTrue(IngestService.isGenericPredicate("采用"));
    }

    @Test
    void singleCharAndBlankAreAlsoGeneric() {
        assertTrue(IngestService.isGenericPredicate("用"));
        assertTrue(IngestService.isGenericPredicate(null));
        assertTrue(IngestService.isGenericPredicate("  "));
    }

    @Test
    void specificPredicatesSurvive() {
        assertFalse(IngestService.isGenericPredicate("监听端口"));
        assertFalse(IngestService.isGenericPredicate("使用向量库"));
        assertFalse(IngestService.isGenericPredicate("部署在"));
        assertFalse(IngestService.isGenericPredicate("使用 PostgreSQL"));
    }
}
