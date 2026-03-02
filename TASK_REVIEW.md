# Ranking System Review for Social-Media-First Manhwa Growth

## Goal and framing
Project goal: optimize for social media performance in the manhwa niche (discoverability, repeat viewing, shareability, and consistent posting cadence), not only internal analytics completeness.

This review evaluates:
1. Existing rankings currently shipped.
2. Which rankings are best for social output.
3. Whether daily vs weekly should be used.
4. What to keep, remove, and modify.

---

## 1) What rankings exist today (current state)

## API-level ranking modes
`/api/trending` supports six ranking modes:
- `RATE` (growth/day)
- `ABS` (absolute growth)
- `PCT` (percent growth)
- `TOTAL` (latest total)
- `ENGAGEMENT` (metric/view ratio)
- `ACCELERATION` (change in growth/day between windows)

Notes from implementation:
- Growth modes use a nearest baseline around ~7 days prior.
- Latest snapshots must be recent (`<= 3 days`).
- Baseline spacing must be at least 6 hours.
- Optional `minPreviousValue` is supported (used to reduce tiny-base noise).

## UI panels currently exposed
Current trending panels are:
1. **Velocity** (`RATE`, views/followers depending on source)
2. **Most Followed** (`TOTAL`)
3. **Most Liked** (`TOTAL`)
4. **Most Subscribed** (`TOTAL`)
5. **Most Trending / Breakout** (`PCT` + `minPreviousValue` floor)
6. **Engagement** (`ENGAGEMENT`, likes/views)
7. **Retention Intent** (`ENGAGEMENT`, subscribers/views)
8. **Acceleration** (`ACCELERATION`)

Source compatibility constraints currently applied:
- Likes-based and engagement panels are effectively Tapas-only.
- Follower/subscriber panels are source-normalized by source type.
- Velocity/breakout/acceleration switch metric by source (followers for Asura, views otherwise).

---

## 2) Social-media adequacy assessment (what actually performs)

For social short-form and feed cards, rankings generally perform best when they are:
- immediately understandable,
- emotionally framed (“who’s rising now?”),
- stable enough to trust,
- frequent enough to post daily.

Assessment of current modes:

### A) `RATE` (growth/day) — **Best primary social ranking**
**Why it works:** Balanced between momentum and fairness across different title sizes.
**Social fit:** High. Easy headline: “Fastest-growing today.”
**Recommendation:** Keep as flagship daily ranking.

### B) `PCT` (percent growth) — **Good but requires guardrails**
**Why it works:** Finds breakout stories that social audiences like.
**Risk:** Small-base inflation without flooring.
**Social fit:** High when thresholded (`minPreviousValue`) and clearly labeled.
**Recommendation:** Keep; increase floor logic by source and possibly dynamic floor percentiles.

### C) `TOTAL` (raw totals) — **Good for authority, weaker for novelty**
**Why it works:** “Biggest titles” has broad appeal and trust.
**Risk:** Low churn; can feel repetitive daily.
**Social fit:** Medium for daily, high for weekly.
**Recommendation:** Keep as weekly anchor and occasional daily recap, not as primary daily hook.

### D) `ENGAGEMENT` — **Useful niche ranking, but data-coverage-sensitive**
**Why it works:** Surfaces efficient fandoms, good for “quality of audience” story.
**Risk:** Cross-source inconsistency and sparse metric availability can confuse users.
**Social fit:** Medium when source-scoped and clearly branded.
**Recommendation:** Keep but reposition as source-specific feature ranking.

### E) `ACCELERATION` — **High potential but currently too abstract for broad audience**
**Why it works:** Detects “heating up now,” often ahead of other signals.
**Risk:** Hard to explain quickly; noisy if scrape intervals vary.
**Social fit:** Medium-low for general audience unless reframed.
**Recommendation:** Modify presentation (rename + simplify), keep as secondary expert ranking.

### F) `ABS` (absolute growth) — **Currently underused but strategically important**
**Why it works:** Highlights true scale movers (not only relative movers).
**Risk:** Big titles dominate.
**Social fit:** Medium-high if paired with category framing (“largest net gain”).
**Recommendation:** Add back as explicit panel/series for “biggest gainers”.

---

## 3) Daily vs weekly: which is better?

## Short answer
**Do both**, with different jobs-to-be-done:
- **Daily = discovery + momentum + conversation**
- **Weekly = authority + recap + shareable canon**

## Why both are needed
- Daily-only can become noisy and fatigue users.
- Weekly-only misses trend momentum and posting frequency needed by social platforms.
- Together, they create a content ladder:
  - fast daily hooks,
  - high-confidence weekly summaries.

## Suggested cadence model
- **Daily posts (core):** `RATE`, `PCT`, and one rotating specialty ranking.
- **Weekly posts (anchor):** `TOTAL`, `ABS`, and weekly `RATE` recap.
- **Monthly/occasionally:** deeper acceleration and engagement explainers.

---

## 4) Keep / Remove / Modify recommendations

## KEEP (core)
1. **Velocity (`RATE`)** as #1 flagship ranking.
2. **Breakout (`PCT` + floor)** as #2 flagship ranking.
3. **Totals (`TOTAL`)** but prioritize as weekly authority content.

## KEEP BUT MODIFY
1. **Acceleration**
   - Rename user-facing label from “Acceleration” to something clearer like “Heating Up Fast”.
   - Show comparison cue (e.g., “momentum up vs last window”).
2. **Engagement / Retention Intent**
   - Mark as source-specific where data is strongest.
   - Avoid forcing cross-source comparisons when denominator semantics differ.
3. **Most Followed / Most Subscribed split**
   - Keep normalization logic, but collapse UX naming to one canonical social label such as “Community Size” to reduce confusion.
4. **Breakout floors**
   - Replace static hardcoded floors with source-aware percentile floors or configurable thresholds to reduce random micro-title spikes.

## REMOVE (or de-prioritize from default social feed)
1. **None of the ranking modes need full removal at API level.**
2. **De-prioritize from default UI/social rotation:** panels with weak cross-source comparability (especially dual engagement variants shown simultaneously).

---

## 5) Recommended social ranking lineup (proposed)

## Daily lineup (recommended default)
1. **Fastest Rising Today** (`RATE`) — main post.
2. **Breakout of the Day** (`PCT` with floor) — discovery post.
3. **Heating Up Fast** (`ACCELERATION`, reframed) — optional third post or story.

## Weekly lineup
1. **Biggest Weekly Gainers** (`ABS`) — net winners.
2. **Most Watched / Most Followed This Week** (`TOTAL`) — authority board.
3. **Weekly Momentum Recap** (`RATE`) — consistency check.

## Niche/rotating lineup
- **Fan Efficiency** (`ENGAGEMENT` likes/views)
- **Retention Intent** (`ENGAGEMENT` subscribers/views)

These should be source-tagged and used as specialized content, not always part of the main universal leaderboard.

---

## 6) Concrete product changes to implement next

### P0 (high impact)
1. Add a timeframe parameter for rankings (`window=daily|weekly`) and standardize copy around that window.
2. Add explicit ABS panel to UI and social renderer lineup.
3. Rebrand acceleration panel label/copy for non-technical audiences.

### P1
1. Convert static breakout floor to configurable/source-aware thresholding.
2. Add “confidence badge” metadata (sample size / baseline strength / data freshness) to reduce perceived randomness.
3. Reduce simultaneous engagement variants in default UI; rotate instead.

### P2
1. Introduce composite social score (weighted blend of RATE + PCT + ABS) for one “Editor’s social rank”.
2. Add cohort rankings by genre/theme for niche audience capture.
3. Feed ranking outcomes into social render templates automatically (daily post queue generation).

---

## 7) Final recommendation
For a social-first manhwa tracker, the optimal strategy is:
- **Keep** RATE + PCT as daily backbone.
- **Keep** TOTAL for weekly authority boards.
- **Add/restore prominence** for ABS as weekly “biggest gainers.”
- **Modify** ACCELERATION and ENGAGEMENT to be clearer and context-aware.
- **Run both daily and weekly cadences** because they serve different audience and algorithm needs.

If forced to pick only one cadence, daily wins for platform consistency. But the highest-quality content system is a dual cadence where weekly rankings validate and contextualize daily volatility.
