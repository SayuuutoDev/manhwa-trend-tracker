# Codebase Review: Proposed Follow-up Tasks

## 1) Typo fix task
**Task:** Fix UI copy typo/grammar in trending panel descriptions.

- In `ui/src/App.tsx`, several panel descriptions use the phrase `"Scale ranking by ..."`, which reads like an imperative fragment instead of the intended descriptive sentence.
- Suggested change: replace `"Scale ranking by latest follower count."` and similar strings with `"Scales ranking by latest follower count."` (or rephrase all consistently as `"Ranks titles by ..."`).

## 2) Bug fix task
**Task:** Fix Webtoons dedupe replacement logic when existing rows have null views.

- In `WebtoonsReader`, this condition decides whether a newly scraped card should replace an existing deduped entry:
  - `if (existing == null || (existing.getViews() != null && views > existing.getViews()))`
- If the first entry has `null` views and a later entry has valid views, replacement does **not** happen because `existing.getViews() != null` is false.
- Suggested fix: compare normalized numeric values (e.g., treat null as 0) so richer records always replace weaker ones.

## 3) Comment/documentation discrepancy task
**Task:** Align ranking mode documentation with current API capabilities.

- `CONTEXT.md` states `mode=ABS|RATE|PCT` for `GET /api/trending`.
- The actual backend enum supports additional modes: `TOTAL`, `ENGAGEMENT`, and `ACCELERATION`.
- Suggested fix: update `CONTEXT.md` (and optionally README) so docs match the implemented API contract.

## 4) Test improvement task
**Task:** Replace the single context-loads smoke test with focused behavior tests.

- Current test coverage is only `contextLoads()` in `ScraperWebtoonsApplicationTests`.
- Suggested additions:
  - Unit tests for `TrendingService` ranking-mode dispatch and mapping.
  - Unit tests for `WebtoonsReader.parseViews` edge cases (`K`, `M`, blanks, malformed strings).
  - Controller tests for `/api/trending` limit clamping and default query params.

