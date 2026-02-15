# Manhwa Trend Tracker - Engineering Context

Last updated: 2026-02-15

## 1) Project Purpose
- Aggregate manhwa metrics from multiple sources (Asura, Tapas, Webtoon).
- Persist time-series snapshots for ranking and growth analysis.
- Provide:
- Backend APIs for rankings and batch control.
- Frontend pages for rankings and manual batch execution.

## 2) Macroarchitecture
- `Spring Boot` backend (`src/main/java`) is the system of record.
- `React + Vite` frontend (`ui/`) is a client of backend APIs.
- `PostgreSQL` stores entities, snapshots, Spring Batch metadata, and cover candidates.

### Backend layers
- Batch ingestion:
- Readers: source fetch/parsing (`WebtoonsReader`, `AsuraSeriesReader`, `TapasSeriesReader`).
- Processors: title resolution, enrichment, snapshot creation, metadata updates.
- Batch APIs:
- `BatchControlController` + `BatchControlService` for start/stop/status.
- Ranking APIs:
- `TrendingController` + `TrendingService` + SQL in `MetricSnapshotRepository`.
- Enrichment/support services:
- `MangaUpdatesEnrichmentService` (metadata + aliases + covers).
- `CoverSelectionService` (source-based cover candidate scoring and selection).

### Data model (core)
- `manhwas`: canonical title + selected metadata (`cover_image_url`, `genre`, `description`).
- `metric_snapshots`: source/time/value per metric.
- `manhwa_titles`: aliases + normalized titles per source.
- `manhwa_external_ids`: source IDs + source URLs (used for read links).
- `manhwa_cover_candidates`: per-source cover candidates + quality score.
- Spring Batch metadata tables (`batch_*`) for job runtime state.

### Frontend pages
- `/` trending page:
- Source panels (Asura, Tapas, Webtoon).
- Growth ranking cards with click-through read URL.
- Growth and growth/day shown (to explain RATE ordering).
- `/batches` batch runner page:
- Start/stop jobs.
- Polling status with counters and progress.

## 3) Ranking Design (Current)
- Main endpoint: `GET /api/trending`.
- Parameters: `metric`, `sourceId`, `limit`, `mode=ABS|RATE|PCT`.
- Current default behavior is rate-based ranking for most panels.
- SQL safeguards:
- Latest snapshot must be recent (within 3 days).
- Baseline must be at least 6 hours apart.
- Baseline is nearest point around ~7 days prior, not strict exact 7-day.
- Excluded genres are config-driven:
- `app.ranking.excluded-genres` in `application.properties`.

## 4) Batch Design (Current)
- Jobs:
- `asuraScrapeJob`
- `tapasScrapeJob`
- `webtoonsScrapeJob`
- Batch API:
- `GET /api/batches`
- `GET /api/batches/{jobName}`
- `POST /api/batches/{jobName}/start`
- `POST /api/batches/{jobName}/stop`
- Manual control is expected; scheduler can be gated with `app.scrape.enabled`.

## 5) Source-Specific Rules
- Asura source is `https://asuracomic.net`.
- Hard rule: never use `https://beta.asurascans.com` for scraping.
- Tapas, Webtoon, and MangaUpdates are used as available for metrics/metadata/covers.

## 6) Resolved Bugs (Important History)
- Asura pagination/list parsing only read repeated subset.
- Root cause: parser relied on wrong link shape (`/series/...`) and caught wrong panel.
- Fix: Asura reader now supports actual `series/...` links and normalizes robustly.

- Tapas job appeared permanently running/stuck on batches page (`STOPPING` zombie).
- Root cause: stale Spring Batch execution remained marked running.
- Fix: `BatchControlService` now reconciles stale running executions and marks them failed.
- Config added: `app.batch.stale-execution-seconds=300`.

- Ranking confusion where low absolute growth ranked high.
- Root cause: list sorted by RATE (growth/day), not ABS.
- Fix: frontend now explicitly displays growth/day on ranking cards.

- Missing covers caused broken-looking top ranking cards.
- Fixes:
- Backend trending query now returns default fallback cover when DB cover is null/blank.
- Added static fallback image: `/images/cover-fallback.svg`.

- Invalid Webtoon-hosted cover URLs (`webtoon-phinf.pstatic.net`) failed in practice.
- Operational cleanup performed:
- Removed those URLs from `manhwas.cover_image_url` and `manhwa_cover_candidates`.
- Re-selected best remaining candidates where available.

## 7) Coding Instructions For Future Changes
- Keep source-of-truth logic in backend; frontend should stay presentation-focused.
- For any new source integration:
- Add explicit reader/processor with `@BeforeStep` state reset.
- Save source external URL in `manhwa_external_ids.url`.
- Route covers through `CoverSelectionService` only.
- Any ranking logic change must be done in SQL + reflected in frontend copy.
- When adding filters/exclusions, prefer config (`application.properties`) over hardcoded SQL.
- When changing job control behavior, validate both:
- `/api/batches` payload correctness.
- Start/stop behavior from `/batches` UI.
- Avoid silent failures:
- For batch anomalies, surface deterministic status (`FAILED`/`STOPPED`) rather than lingering running state.

## 8) Operational Notes
- Backend run (scrape profile, no auto scrape):
- `./mvnw -q spring-boot:run -Dspring-boot.run.profiles=scrape -Dspring-boot.run.arguments=--app.scrape.enabled=false`
- Frontend run:
- `cd ui && npm run dev`
- Database backup convention:
- Store compressed dumps under `backups/` as `manhwa_trends_YYYYMMDD_HHMMSS.sql.gz`.

## 9) What To Update In This File
- Update this file whenever you change:
- Architecture responsibilities.
- Ranking formula/filter behavior.
- Batch control semantics.
- Source-specific scraping constraints.
- Any bugfix that changes expected runtime behavior.
