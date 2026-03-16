package com.example.src.service;

import com.example.src.dto.MatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core similarity matching service.
 *
 * Algorithm mirrors the Python reference in test_similarity.py:
 *   1. For each pattern, compute cosine similarity between the query embedding
 *      and every reference embedding stored for that pattern.
 *   2. Take the MAX similarity for each pattern (best-match-wins strategy).
 *   3. The pattern with the highest max similarity wins.
 *   4. Apply threshold: below threshold → UNCERTAIN.
 *   5. Top-2 margin rule: if bestScore >= threshold but (bestScore - secondBestScore) < margin,
 *      return UNCERTAIN to reduce wrong-but-high-score outcomes.
 *
 * Cosine similarity formula:
 *   cosine(a, b) = dot(a, b) / (|a| * |b|)
 *
 * Because reference embeddings are already L2-normalized (|a|=1) and the query
 * embedding is normalized in the controller before calling this service,
 * cosine similarity reduces to a plain dot product.
 */
@Service
public class SimilarityService {

    private static final Logger log = LoggerFactory.getLogger(SimilarityService.class);

    private static final double THRESHOLD_HIGH = 0.80;

    /**
     * Computes cosine similarity between two L2-normalized vectors.
     * For unit vectors, cosine similarity == dot product.
     *
     * Preconditions:
     *   - vec1.length == vec2.length
     *   - Both vectors must be L2-normalized before calling this method.
     *     (Reference vectors are pre-normalized in the database;
     *      query vector must be normalized via l2Normalize() first.)
     *
     * @param vec1 L2-normalized float array
     * @param vec2 L2-normalized float array
     * @return dot product (equivalent to cosine similarity for unit vectors)
     */
    public double cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch: " + vec1.length + " vs " + vec2.length);
        }
        double dot = 0.0;
        for (int i = 0; i < vec1.length; i++) {
            dot += (double) vec1[i] * vec2[i];
        }
        return dot;
    }

    /**
     * L2-normalizes a float vector in-place (returns a new array).
     * Only the query embedding needs normalization at runtime; database
     * embeddings are already normalized.
     *
     * @param vector raw embedding from the ONNX model
     * @return a new float[] with unit L2 norm
     * @throws IllegalArgumentException if the vector is all zeros
     */
    public float[] l2Normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Embedding vector must not be null or empty");
        }
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }
        double norm = Math.sqrt(sumSq);
        if (norm == 0.0) {
            throw new IllegalArgumentException("Cannot L2-normalize a zero vector (all elements are 0)");
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }

    /**
     * Finds the best matching pattern for a given query embedding.
     *
     * Mirrors the Python find_best_match() logic exactly:
     *   - For each pattern: compute max(cosine(query, ref)) across all reference embeddings.
     *   - Best pattern = argmax(max_similarities).
     *   - Confidence: HIGH (>=0.80), MEDIUM (>=threshold, <0.80), UNCERTAIN (<threshold).
     *
     * @param queryEmbedding  L2-normalized query embedding (from the uploaded image)
     * @param database        loaded reference database (key=patternCode, value=list of embeddings)
     * @param threshold       similarity threshold below which the result is UNCERTAIN
     * @param margin          minimum gap (best - secondBest) required for MATCH when above threshold
     * @return populated MatchResult with scores, best match, confidence, and decision
     */
    public MatchResult findBestMatch(float[] queryEmbedding,
                                     Map<String, List<float[]>> database,
                                     double threshold,
                                     double margin) {
        if (database == null || database.isEmpty()) {
            throw new IllegalStateException("Pattern database is empty - cannot perform matching");
        }
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            throw new IllegalArgumentException("Query embedding must not be null or empty");
        }

        // --- Step 1: for every pattern, find the MAX similarity across all its embeddings ---
        Map<String, Double> patternScores = new LinkedHashMap<>();

        for (Map.Entry<String, List<float[]>> entry : database.entrySet()) {
            String patternCode = entry.getKey();
            List<float[]> embeddings = entry.getValue();

            if (embeddings == null || embeddings.isEmpty()) {
                log.warn("Pattern {} has no reference embeddings - skipping", patternCode);
                continue;
            }

            double maxSim = Double.NEGATIVE_INFINITY;
            for (float[] refEmbedding : embeddings) {
                double sim = cosineSimilarity(queryEmbedding, refEmbedding);
                if (sim > maxSim) {
                    maxSim = sim;
                }
            }
            patternScores.put(patternCode, maxSim);
        }

        if (patternScores.isEmpty()) {
            throw new IllegalStateException("No valid patterns found after filtering");
        }

        // --- Step 2: find best and second-best scores ---
        String bestPattern = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : patternScores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestPattern = entry.getKey();
            }
        }
        double secondBestScore = secondBestFromScores(patternScores, bestPattern);

        // --- Step 3: apply threshold + margin rule (decision) ---
        boolean marginOk = patternScores.size() == 1 || (bestScore - secondBestScore >= margin);
        boolean isMatch = bestScore >= threshold && marginOk;
        String decision = isMatch ? "MATCH" : "UNCERTAIN";

        String confidence;
        if (bestScore >= THRESHOLD_HIGH) {
            confidence = "HIGH";
        } else if (bestScore >= threshold) {
            confidence = "MEDIUM";
        } else {
            confidence = "UNCERTAIN";
        }

        log.debug("Best match: pattern={}, score={}, secondBest={}, confidence={}, decision={}",
                bestPattern, String.format("%.4f", bestScore), secondBestScore, confidence, decision);

        // --- Step 4: build and return MatchResult ---
        MatchResult result = new MatchResult();
        result.setPatternCode(bestPattern);
        result.setSimilarityScore(bestScore);
        result.setSecondBestScore(patternScores.size() > 1 ? secondBestScore : null);
        result.setMarginUsed(margin);
        result.setDecision(decision);
        result.setIsMatch(isMatch);
        result.setConfidence(confidence);
        result.setThreshold(threshold);
        result.setAllPatternScores(Collections.unmodifiableMap(patternScores));
        return result;
    }

    private static double secondBestFromScores(Map<String, Double> patternScores, String excludePattern) {
        double second = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : patternScores.entrySet()) {
            if (entry.getKey().equals(excludePattern)) continue;
            if (entry.getValue() > second) second = entry.getValue();
        }
        return second == Double.NEGATIVE_INFINITY ? Double.NaN : second;
    }

    /**
     * Optimized variant of findBestMatch() that operates on a flat, array-based
     * snapshot of the database to eliminate per-request Map/List allocations.
     */
    public MatchResult findBestMatch(float[] queryEmbedding,
                                     DatabaseService.FlatSnapshot snapshot,
                                     double threshold,
                                     double margin) {
        if (snapshot == null || snapshot.patternCodes() == null || snapshot.patternCodes().length == 0) {
            throw new IllegalStateException("Pattern database is empty - cannot perform matching");
        }
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            throw new IllegalArgumentException("Query embedding must not be null or empty");
        }

        String[] patternCodes = snapshot.patternCodes();
        float[][][] allEmbeddings = snapshot.embeddings();

        Map<String, Double> patternScores = new LinkedHashMap<>(patternCodes.length);

        String bestPattern = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < patternCodes.length; i++) {
            String patternCode = patternCodes[i];
            float[][] refs = allEmbeddings[i];

            if (refs == null || refs.length == 0) {
                log.warn("Pattern {} has no reference embeddings - skipping", patternCode);
                continue;
            }

            int dim = refs[0].length;
            if (dim != queryEmbedding.length) {
                log.warn("Dimension mismatch for pattern {}: ref dim={} vs query dim={}",
                        patternCode, dim, queryEmbedding.length);
                continue;
            }

            double maxSim = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < refs.length; j++) {
                float[] ref = refs[j];
                double dot = 0.0;
                for (int k = 0; k < dim; k++) {
                    dot += (double) queryEmbedding[k] * ref[k];
                }
                if (dot > maxSim) {
                    maxSim = dot;
                }
            }

            patternScores.put(patternCode, maxSim);

            if (maxSim > bestScore) {
                bestScore = maxSim;
                bestPattern = patternCode;
            }
        }

        if (patternScores.isEmpty()) {
            throw new IllegalStateException("No valid patterns found after filtering");
        }

        double secondBestScore = secondBestFromScores(patternScores, bestPattern);

        boolean marginOk = patternScores.size() == 1 || (bestScore - secondBestScore >= margin);
        boolean isMatch = bestScore >= threshold && marginOk;
        String decision = isMatch ? "MATCH" : "UNCERTAIN";

        String confidence;
        if (bestScore >= THRESHOLD_HIGH) {
            confidence = "HIGH";
        } else if (bestScore >= threshold) {
            confidence = "MEDIUM";
        } else {
            confidence = "UNCERTAIN";
        }

        log.debug("Best match: pattern={}, score={}, secondBest={}, confidence={}, decision={}",
                bestPattern, String.format("%.4f", bestScore), secondBestScore, confidence, decision);

        MatchResult result = new MatchResult();
        result.setPatternCode(bestPattern);
        result.setSimilarityScore(bestScore);
        result.setSecondBestScore(patternScores.size() > 1 ? secondBestScore : null);
        result.setMarginUsed(margin);
        result.setDecision(decision);
        result.setIsMatch(isMatch);
        result.setConfidence(confidence);
        result.setThreshold(threshold);
        result.setAllPatternScores(Collections.unmodifiableMap(patternScores));

        return result;
    }
}
