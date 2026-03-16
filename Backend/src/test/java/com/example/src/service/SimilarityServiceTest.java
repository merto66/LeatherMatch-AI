package com.example.src.service;

import com.example.src.dto.MatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimilarityService: second-best score computation and margin rule.
 */
class SimilarityServiceTest {

    private SimilarityService similarityService;

    @BeforeEach
    void setUp() {
        similarityService = new SimilarityService();
    }

    /** L2-normalized vector [1, 0, 0]. */
    private static float[] unitX() {
        return new float[]{1f, 0f, 0f};
    }

    /** L2-normalized vector giving dot 0.97 with [1,0,0]: e.g. [0.97f, 0.243f, 0f] (approx). */
    private static float[] ref97() {
        float[] v = new float[]{0.97f, 0.243f, 0f};
        return new SimilarityService().l2Normalize(v);
    }

    /** L2-normalized vector giving dot 0.75 with [1,0,0]. */
    private static float[] ref75() {
        float[] v = new float[]{0.75f, 0.661f, 0f};
        return new SimilarityService().l2Normalize(v);
    }

    @Test
    void marginRule_gapAboveMargin_returnsMatch() {
        Map<String, List<float[]>> db = new LinkedHashMap<>();
        db.put("A", List.of(unitX()));           // score 1.0
        db.put("B", List.of(ref97()));             // score ~0.97, gap = 0.03
        float[] query = unitX();

        MatchResult result = similarityService.findBestMatch(query, db, 0.70, 0.03);

        assertEquals("A", result.getPatternCode());
        assertEquals(1.0, result.getSimilarityScore(), 1e-5);
        assertEquals(0.97, result.getSecondBestScore(), 0.01);
        assertEquals("MATCH", result.getDecision());
        assertTrue(Boolean.TRUE.equals(result.getIsMatch()));
    }

    @Test
    void marginRule_gapBelowMargin_returnsUncertain() {
        Map<String, List<float[]>> db = new LinkedHashMap<>();
        db.put("A", List.of(unitX()));             // score 1.0
        db.put("B", List.of(ref97()));             // score ~0.97, gap = 0.03
        float[] query = unitX();

        MatchResult result = similarityService.findBestMatch(query, db, 0.70, 0.05);

        assertEquals("A", result.getPatternCode());
        assertEquals(1.0, result.getSimilarityScore(), 1e-5);
        assertEquals("UNCERTAIN", result.getDecision());
        assertFalse(Boolean.TRUE.equals(result.getIsMatch()));
    }

    @Test
    void marginRule_singlePattern_alwaysMatchWhenAboveThreshold() {
        Map<String, List<float[]>> db = new LinkedHashMap<>();
        db.put("Only", List.of(unitX()));
        float[] query = unitX();

        MatchResult result = similarityService.findBestMatch(query, db, 0.70, 0.10);

        assertEquals("Only", result.getPatternCode());
        assertNull(result.getSecondBestScore());
        assertEquals("MATCH", result.getDecision());
        assertTrue(Boolean.TRUE.equals(result.getIsMatch()));
    }

    @Test
    void belowThreshold_returnsUncertainRegardlessOfMargin() {
        Map<String, List<float[]>> db = new LinkedHashMap<>();
        db.put("A", List.of(ref75()));             // score ~0.75
        db.put("B", List.of(ref97()));             // best ~0.97
        float[] query = unitX();

        MatchResult result = similarityService.findBestMatch(query, db, 0.98, 0.01);

        assertEquals("B", result.getPatternCode());
        assertEquals("UNCERTAIN", result.getDecision());
        assertFalse(Boolean.TRUE.equals(result.getIsMatch()));
    }

    @Test
    void resultIncludesMarginUsedAndDecision() {
        Map<String, List<float[]>> db = new LinkedHashMap<>();
        db.put("P1", List.of(unitX()));
        db.put("P2", List.of(ref97()));
        float[] query = unitX();

        MatchResult result = similarityService.findBestMatch(query, db, 0.70, 0.02);

        assertEquals(0.02, result.getMarginUsed(), 1e-6);
        assertNotNull(result.getDecision());
        assertTrue(result.getDecision().equals("MATCH") || result.getDecision().equals("UNCERTAIN"));
    }
}
