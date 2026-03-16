# How to calibrate LeatherMatch-AI

This guide explains how to tune the matching decision (MATCH vs UNCERTAIN) using **threshold** and **margin** so that wrong-but-high-score outcomes are reduced without retraining the model.

## Parameters

### `similarity.threshold` (default: 0.70)

- Minimum best-score required to consider a result confident.
- If the best pattern’s similarity score is **below** this value, the decision is always **UNCERTAIN** (manual check recommended).
- Typical range: **0.70–0.75**. Higher → fewer MATCHes, more UNCERTAIN; lower → more MATCHes, higher risk of wrong-but-confident.

### `similarity.margin` (default: 0.03)

- **Top-2 gap rule**: even when `bestScore >= threshold`, the system also requires that the gap between the best and second-best pattern score is at least `margin`.
- If `(bestScore - secondBestScore) < margin`, the decision is **UNCERTAIN** (close call between two patterns).
- Typical range: **0.02–0.05**. Higher → more borderline cases become UNCERTAIN; lower → more MATCHes, including some risky ones.

### How they work together

- **MATCH** is returned only when:
  1. `bestScore >= threshold`, and  
  2. Either there is only one pattern, or `(bestScore - secondBestScore) >= margin`.
- Otherwise the result is **UNCERTAIN**.

Example: threshold = 0.72, margin = 0.03. Best = 0.74, second = 0.72 → gap = 0.02 < 0.03 → **UNCERTAIN**, even though the score is above threshold. This avoids declaring a MATCH when two patterns are almost tied.

---

## Recommended starting values

| Parameter | Suggested start | Notes |
|-----------|-----------------|--------|
| **threshold** | **0.70** or **0.72** | Matches `application.properties` default; raise if you see too many wrong MATCHes. |
| **margin**    | **0.03**         | Good balance; increase to 0.04–0.05 if wrong-but-confident cases persist. |

You can set these in `application.properties` or at runtime via the admin API (see below). Changes take effect immediately; no restart needed.

---

## Step-by-step calibration

1. **Run the system**  
   Use it for a few days (or at least dozens of matches) so that `match_logs` has real data.

2. **Inspect metrics**  
   Call the admin metrics endpoint (HTTP Basic Auth required):
   ```http
   GET /api/admin/metrics
   ```
   The response includes:
   - **totalMatches**, **matchCount**, **uncertainCount**, **matchRate**, **uncertainRate**
   - **latency**: **avg_ms**, **p95_ms** (from latest 1000 logs)
   - **scoreHistogram**: distribution of best scores in buckets
   - **perPattern**: count and average best score per predicted pattern

3. **Interpret the numbers**
   - **matchRate** vs **uncertainRate**: if almost everything is MATCH and you still see wrong matches in the factory, consider raising **threshold** or **margin**.
   - **scoreHistogram**: many scores just above threshold (e.g. 0.70–0.75) with a tight second-best → good candidates for the margin rule; try increasing **margin**.
   - **perPattern**: patterns with low **avgBestScore** or many UNCERTAINs may need more reference images or a pattern-specific strategy later.

4. **Adjust settings**
   - **Threshold**:  
     `PUT /api/admin/settings/threshold` with body `{"threshold": 0.74}`.
   - **Margin**:  
     `PUT /api/admin/settings/margin` with body `{"margin": 0.04}`.

5. **Re-check**  
   After more matches, call `/api/admin/metrics` again. Aim for:
   - Fewer wrong-but-confident MATCHes (margin rule doing its job).
   - A manageable uncertain rate (operators can handle a share of “manual check” results).

6. **Trade-off**  
   - Higher threshold or margin → more UNCERTAIN, fewer wrong MATCHes.  
   - Lower threshold or margin → more MATCHes, higher risk of errors.  
   Tune until the balance fits your workflow.

---

## How the margin reduces wrong-but-high-score

- **Without margin**: any score above threshold can be MATCH. If best = 0.78 and second = 0.76, the system might still return MATCH even though the top two patterns are very close (risky).
- **With margin (e.g. 0.03)**: same example, gap = 0.02 < 0.03 → decision becomes **UNCERTAIN**. The operator is prompted to check, and you avoid committing to the wrong pattern.

So: **margin** adds a “safety gap” so that only clearly leading patterns get MATCH; close races become UNCERTAIN and can be reviewed.

---

## Where settings are stored

- **Runtime**: in-memory and in SQLite `settings` table (key `similarity.threshold`, `similarity.margin`). Updated via admin API or at startup from `application.properties`.
- **Defaults**: `Backend/src/main/resources/application.properties` (`similarity.threshold=0.70`, `similarity.margin=0.03`).

For more on the roadmap and phases, see `ROADMAP.md`. For API details, see `README.md`.
