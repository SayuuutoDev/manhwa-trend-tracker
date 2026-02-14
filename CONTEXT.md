# Project Context (Codex Session Summary)

Last updated: 2026-02-14

Purpose
- Expand the manhwa tracker to support multiple sources and unify snapshots across alternate titles.
- Use a large `series.json` (≈1.97 GB, 188,760 entries) to populate alias titles and external IDs.

Recent changes and fixes
- Added Flyway migrations and moved off `spring.jpa.hibernate.ddl-auto=update` to `validate`:
  - `src/main/resources/db/migration/V0__create_manhwas_metric_snapshots.sql`
  - `src/main/resources/db/migration/V1__create_titles_external_ids.sql`
  - `pom.xml` includes `flyway-core` and `flyway-database-postgresql`.
- Importer hardened:
  - In-run external ID cache prevents duplicates.
  - Batch flush with fallback on constraint violations.
  - Title dedupe by normalized title.
  - Progress interval config `app.series.import.progress-interval`.
  - Canonical title lookup fallback to avoid duplicate `manhwas`.
- Scrape job gating:
  - `app.scrape.enabled=false` disables startup + scheduled scrapes.
  - One-off Asura run supported via `app.asura.run-once=true`.
  - One-off Tapas run supported via `app.tapas.run-once=true`.

Key findings about `series.json`
- `all_titles` present for ~186k entries; missing for 2,706.
- `en_primary_title` present for ~112k entries.
- Only 26 entries have neither `en_primary_title` nor `all_titles`.
- Max `all_titles` length observed: 262.

Schema additions (new entities)
- `src/main/java/com/manhwa/tracker/webtoons/model/TitleSource.java`
  - Enum for title/id sources (WEBTOONS, ASURA, TAPAS, MANGADEX, ANILIST, MYANIMELIST, KITSU, MANGAUPDATES, OTHER).
- `src/main/java/com/manhwa/tracker/webtoons/model/ManhwaTitle.java`
  - Table: `manhwa_titles`
  - Fields: `manhwaId`, `title`, `normalizedTitle`, `source`, `language`, `canonical`, `confidence`
  - Unique constraint: `(manhwaId, normalizedTitle, source, language)`
  - Indexes on `manhwaId`, `normalizedTitle`, `source`
- `src/main/java/com/manhwa/tracker/webtoons/model/ManhwaExternalId.java`
  - Table: `manhwa_external_ids`
  - Unique constraint: `(source, externalId)`
  - Index on `manhwaId`

Repositories
- `src/main/java/com/manhwa/tracker/webtoons/repository/ManhwaTitleRepository.java`
  - Lookup by normalized title / source / manhwaId.
- `src/main/java/com/manhwa/tracker/webtoons/repository/ManhwaExternalIdRepository.java`
  - Lookup by `(source, externalId)`.

Importer
- `src/main/java/com/manhwa/tracker/webtoons/importer/SeriesJsonImporter.java`
  - Streaming Jackson parser for `series.json` to avoid loading 2GB in memory.
  - Resolves existing manhwa by external IDs first (MANGADEX, ANILIST, MYANIMELIST, KITSU, MANGAUPDATES).
  - If no match, creates a new `Manhwa` using `en_primary_title` or first available title.
  - Inserts aliases into `manhwa_titles` with `normalizedTitle` and canonical flag.
  - Inserts external IDs into `manhwa_external_ids`.
  - Gated by property: `app.series.import.enabled=true`.
  - Config:
    - `app.series.import.path` (default: `series.json`)
    - `app.series.import.batch-size` (default: 500)

Title normalization helper
- `src/main/java/com/manhwa/tracker/webtoons/service/TitleNormalizer.java`
  - NFKD normalize, strip diacritics, lowercase, keep `[a-z0-9]`, collapse spaces.

How to run the importer
- Run with:
  - `./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--app.series.import.enabled=true"`
- Optional:
  - `--app.series.import.path=series.json`
  - `--app.series.import.batch-size=500`
  - `--app.series.import.progress-interval=10000`

Import results (clean DB, 2026-02-07)
- Processed: 188,760
- Created manhwas: 167,739
- Matched manhwas: 21,021
- Titles added: 417,400
- External IDs added: 356,823

Bug: Asura scrape created new manhwas instead of linking aliases
- Symptom: Asura created new manhwas (e.g., “Revenge of the Iron-Blooded Sword Hound”) even though aliases existed for “Revenge of the Baskerville Bloodhound.”
- Fix: `AsuraSeriesProcessor` now resolves by normalized title in `manhwa_titles` first, then canonical title, and skips if no match. No new manhwas were created in subsequent runs.
 - Added skip summary on shutdown (count + first 10).

Bug: Webtoons scrape created new manhwas instead of linking aliases
- Fix: `WebtoonsProcessor` now resolves by normalized title in `manhwa_titles` first, then canonical title, and skips if no match. Added a guard report to log skipped titles on shutdown.

Bug: Flyway failed on non-empty schema without history table
- Fix path used: dropped schema and reran Flyway migrations. (Alternative would be `spring.flyway.baseline-on-migrate=true`.)

Tapas scraping
- Endpoint discovered from network: `https://story-api.tapas.io/cosmos/api/v1/landing/genre?category_type=COMIC&subtab_id=17&page=2&size=25`
- Added Tapas batch:
  - Reader: `TapasSeriesReader` (paged API fetch, reads view/subscriber/like counts).
  - Processor: `TapasSeriesProcessor` matches by normalized title then canonical, skips if no match, and writes 3 snapshots (VIEWS, SUBSCRIBERS, LIKES).
  - Adds `TitleSource.TAPAS` external IDs and aliases on match.
  - Scheduler: `app.tapas.cron` (defaults to `app.snapshot.cron`).
  - Run-once: `app.tapas.run-once=true`.
- Metrics enum updated: `MetricType` now includes `SUBSCRIBERS` and `LIKES`.
- Tapas run on 2026-02-07 23:44:
  - No new manhwas created.
  - 1655 snapshots each for VIEWS/SUBSCRIBERS/LIKES.
  - 445 titles skipped (logged).

Top views query (latest snapshots)
- Latest per-source views summed across sources:
  - My Giant Nerd Boyfriend (77,500,000)
  - The Beginning After the End: Side Story - Jasmine: Wind-Borne (49,851,470)
  - Sk8trboi (47,180,370)
  - FANGS (45,508,357)
  - My Weird Roommate (33,883,158)
  - Solo Leveling (28,467,028)
  - The Gamer (22,800,000)
  - Hibana (20,732,498)
  - A Business Proposal (18,589,737)
  - Positively Yours (17,296,818)

Notes / open items
- Imported aliases currently use `TitleSource.OTHER`. Consider mapping by source later when integrating Tapas/Webtoons/etc.

Recent changes (2026-02-13)
- Added trending API for frontend MVP:
  - Endpoint: `GET /api/trending?metric=VIEWS&limit=10`
  - Computes growth = latest snapshot − previous snapshot (prefers most recent snapshot ≥7 days older than latest; falls back to oldest snapshot if none).
  - Returns `manhwaId`, `title`, `metricType`, `latestValue`, `latestAt`, `previousValue`, `previousAt`, `growth`.
  - Files:
    - `src/main/java/com/manhwa/tracker/webtoons/api/TrendingController.java`
    - `src/main/java/com/manhwa/tracker/webtoons/service/TrendingService.java`
    - `src/main/java/com/manhwa/tracker/webtoons/repository/MetricSnapshotRepository.java`
    - `src/main/java/com/manhwa/tracker/webtoons/repository/TrendingProjection.java`
    - `src/main/java/com/manhwa/tracker/webtoons/model/TrendingManhwaDTO.java`
- Added `spring-boot-starter-web` to `pom.xml` to expose REST endpoints.
- Created frontend scaffold under `ui/` (Vite + React + TypeScript):
  - Files: `ui/package.json`, `ui/vite.config.ts`, `ui/index.html`, `ui/tsconfig*.json`, `ui/src/*`.
  - Proxy `/api` to `http://localhost:8080` for local development.
  - MVP UI fetches `/api/trending` and renders Top 10 list with metric selector and animations.
- Fixed Tapas duplicate key crash by checking for existing `manhwa_titles` entries before insert:
  - Added `existsByManhwaIdAndNormalizedTitleAndSourceAndLanguage*` methods in `ManhwaTitleRepository`.
  - `TapasSeriesProcessor` now skips alias insert when it already exists (language-aware).
- Trending API supports optional source filtering:
  - `/api/trending` now accepts `sourceId` (e.g., `2` for Asura).
  - Backend query filters snapshots by `source_id` when provided.
- UI now requests Asura-only trending (Followers metric + `sourceId=2`), and the selector is restricted to Asura followers.
- Trending responses now include `coverImageUrl` from `manhwas.cover_image_url`; UI shows a cover image with initials fallback.
- Asura batch now refreshes `manhwas` metadata (cover image URL, description, genre) on every run using scraped page data.
- UI restyled with a neon-ink palette and larger, clipped cover cards for a more premium, cinematic feel.
- Fixed tilt interaction by animating a custom `--rise-y` property instead of overriding card transforms.
- 2026-02-14: Covers are now dominant on trend cards (merged look, 32% width -> 40% on hover) with a cropped zoom and a default SVG cover for missing images.
- 2026-02-14: Cover layout revised: cover now anchors the left side of the card (45% column), rank overlays the cover, and hover scales the image instead of resizing the column.
- 2026-02-14: Cover zoom adjusted to show more art; background updated to a lo-fi animated gradient wash.
- 2026-02-14: Background refined to a “midnight café” palette (warm amber + soft violet + muted blue).
- 2026-02-14: Cover crop shifted downward to avoid showing the upper part of the image.
- 2026-02-14: Cover rendering switched to a background-image container to enforce crop position and zoom reliably (now set to 30% vertical position).
- 2026-02-14: Backend config now uses env vars for DB creds, added snapshot index migration, scraping gated behind `scrape` profile, and API error handling + UI retry button.

Instruction
- Always update `CONTEXT.md` after making changes (features, fixes, migrations, configs, or new files).

Recent docs
- Added `README.md` with run instructions for backend, scraping, frontend, and troubleshooting.
- 2026-02-14: Removed entry animation on trend cards; cover image now starts at ~20% width of card and expands to ~30% on hover with a cropped, zoomed look.
- 2026-02-14: Added batch control and progress tracking:
  - Backend API to trigger + inspect Spring Batch scrapers:
    - `GET /api/batches` (list jobs with status/counters)
    - `GET /api/batches/{jobName}` (single job view)
    - `POST /api/batches/{jobName}/start` (manual run)
  - New files:
    - `src/main/java/com/manhwa/tracker/webtoons/api/BatchControlController.java`
    - `src/main/java/com/manhwa/tracker/webtoons/service/BatchControlService.java`
    - `src/main/java/com/manhwa/tracker/webtoons/model/BatchJobView.java`
    - `src/main/java/com/manhwa/tracker/webtoons/model/BatchStartResponse.java`
  - `ApiExceptionHandler` now returns 400 for invalid job names and 409 when a job is already running.
- 2026-02-14: Frontend now includes a Scraper Control panel:
  - Run buttons for Asura/Webtoons/Tapas jobs.
  - Polls `/api/batches` every 2 seconds.
  - Live execution state with status pills, progress bars, and read/write/skip counters.
  - Updated files: `ui/src/App.tsx`, `ui/src/api.ts`, `ui/src/types.ts`, `ui/src/index.css`.
- 2026-02-14: Fixed reader state reuse across manual reruns:
  - `WebtoonsReader`, `AsuraSeriesReader`, and `TapasSeriesReader` now reset in-memory paging/index state at step start via `@BeforeStep`.
  - This allows repeated executions from the new batch control UI without stale reader state.
- 2026-02-14: Frontend pages split so batch controls are separate from trending:
  - `/` now shows only the Trending leaderboard UI.
  - `/batches` now shows only the Batch Runner controls and live execution progress.
  - Header navigation toggles between the two pages using browser history paths.
- 2026-02-14: Fixed Batch Runner start for Webtoons job:
  - `BatchControlService` now resolves runnable jobs by Spring Batch job name (`job.getName()`) instead of bean name.
  - This fixes `POST /api/batches/webtoonsScrapeJob/start` returning "Unknown job".
- 2026-02-14: Trending page now has a new Tapas.io growth nav panel:
  - Added panel switcher options for Asura and Tapas in `ui/src/App.tsx`.
  - Tapas panel requests `/api/trending` with `metric=VIEWS` and `sourceId=3`.
  - Trending hero text and ranking title now update based on selected source panel.
- 2026-02-14: Tapas scraper now ingests and applies cover URLs to `manhwas`:
  - `TapasSeriesReader` now extracts cover from `assetProperty.bookCoverImage.path` with fallback to `assetProperty.thumbnailImage.path`.
  - `TapasSeriesDTO` now carries `coverImageUrl`.
  - `TapasSeriesProcessor` now overwrites `manhwas.cover_image_url` for matched titles when Tapas provides a non-blank URL.
- 2026-02-14: Trending growth formula and UX were hardened:
  - API now supports ranking modes via `mode=ABS|RATE|PCT` (default `RATE`).
  - SQL baseline now picks the snapshot nearest to ~7 days before latest (instead of oldest fallback).
  - Added data-quality guards: latest snapshot must be fresh (within 3 days) and baseline gap must be at least 6 hours.
  - Response now includes `baselineDays`, `growthPerDay`, `growthPercent`, and `rankingMode`.
  - Frontend now displays explanatory copy that ranking is rate-based and uses the nearest available window to 7 days, with current average baseline days shown.
- 2026-02-14: Trending page now includes a Webtoon growth panel:
  - Added Webtoon source option in the trending panel switcher (`metric=VIEWS`, `sourceId=1`, `mode=RATE`).
  - Users can now switch between Asura, Tapas, and Webtoon growth rankings from the same trending page.
- 2026-02-14: Cover quality selection added across all batches:
  - New table + model to store per-source cover candidates:
    - `src/main/resources/db/migration/V4__create_cover_candidates.sql`
    - `src/main/java/com/manhwa/tracker/webtoons/model/ManhwaCoverCandidate.java`
    - `src/main/java/com/manhwa/tracker/webtoons/repository/ManhwaCoverCandidateRepository.java`
  - New service:
    - `src/main/java/com/manhwa/tracker/webtoons/service/CoverSelectionService.java`
    - Upserts one cover candidate per `(manhwa, source)`, scores candidates, and writes the best URL into `manhwas.cover_image_url`.
    - Source priority favors higher-quality providers (`ASURA > WEBTOONS > TAPAS > MANGAUPDATES`) with URL heuristics for thumbnails/quality hints.
  - All cover writes now flow through cover selection:
    - `AsuraSeriesProcessor` -> `TitleSource.ASURA`
    - `WebtoonsProcessor` -> `TitleSource.WEBTOONS`
    - `TapasSeriesProcessor` -> `TitleSource.TAPAS`
    - `MangaUpdatesEnrichmentService` -> `TitleSource.MANGAUPDATES`
  - Runtime verification:
    - `webtoonsScrapeJob` and `asuraScrapeJob` complete successfully after integration.
    - `manhwa_cover_candidates` populated as expected.
    - Example fixed: `The Novel's Extra` now prefers Asura cover URL over MangaUpdates cover URL.
- 2026-02-14: Ranking cards now link to original reading pages:
  - Trending API now returns `readUrl` per item:
    - `TrendingProjection` + `TrendingManhwaDTO` include `readUrl`.
    - `MetricSnapshotRepository.findTrending` now resolves a source-specific URL from `manhwa_external_ids` based on `sourceId`:
      - `1 -> WEBTOONS`
      - `2 -> ASURA`
      - `3 -> TAPAS`
  - Frontend trend cards are clickable when `readUrl` is present:
    - `ui/src/App.tsx` renders cards as `<a target="_blank" rel="noopener noreferrer">`.
    - `ui/src/types.ts` includes `readUrl`.
    - `ui/src/index.css` styles clickable cards via `.trend-card.is-link`.
  - Scrapers now persist source page URLs in `manhwa_external_ids.url`:
    - `WebtoonsReader` now captures `seriesUrl`; `WebtoonsProcessor` upserts `TitleSource.WEBTOONS` external IDs with URL.
    - `AsuraSeriesProcessor` upserts `TitleSource.ASURA` external IDs with URL.
    - `TapasSeriesProcessor` now stores `https://tapas.io/series/{seriesId}` and backfills URL on existing Tapas external IDs.
  - Added migration `V5__backfill_external_id_urls.sql` to populate missing URLs for existing rows:
    - Tapas numeric IDs -> `https://tapas.io/series/{external_id}`
    - Asura/Webtoons URL-shaped `external_id` copied into `url`.
- 2026-02-14: MangaUpdates enrichment integrated across all scrape processors:
  - New service: `src/main/java/com/manhwa/tracker/webtoons/service/MangaUpdatesEnrichmentService.java`.
  - Called from `TapasSeriesProcessor`, `WebtoonsProcessor`, and `AsuraSeriesProcessor` after title resolution.
  - Enrichment behavior:
    - Uses existing `MANGAUPDATES` external ID first, then falls back to MU title search.
    - Ignores legacy non-numeric MU IDs for direct series lookup and falls back to search.
    - Upserts MU aliases into `manhwa_titles` (`source=MANGAUPDATES`).
    - Updates `manhwas` metadata (cover/description/genre) from MU records.
    - Adds lightweight retries for MU `429/5xx` responses.
  - Config added in `application.properties`:
    - `app.mangaupdates.enabled`
    - `app.mangaupdates.base-url`
    - `app.mangaupdates.user-agent`
    - `app.mangaupdates.request-delay-ms`
    - `app.mangaupdates.search.max-results`
- 2026-02-14: Fixed batch failures caused by duplicate MU external IDs:
  - Root cause: duplicate `manhwa_external_ids` rows for the same `(manhwa_id, source)` caused non-unique query failures during enrichment.
  - `ManhwaExternalIdRepository` now supports `findAllByManhwaIdAndSource(...)`.
  - Enrichment lookup/upsert now handles multi-row legacy data safely and updates newest row instead of blindly inserting.
  - New Flyway migration:
    - `src/main/resources/db/migration/V3__dedupe_and_constrain_external_ids.sql`
    - Deduplicates existing rows by `(manhwa_id, source)` keeping the newest ID.
    - Adds DB constraint `uk_external_id_manhwa_source` (unique on `(manhwa_id, source)`).
  - Verification:
    - `webtoonsScrapeJob` and `asuraScrapeJob` complete from `/api/batches/.../start`.
    - Example record fixed: `The Twins' New Life` now has MU cover `https://cdn.mangaupdates.com/image/i487550.jpg`.
    - Duplicate `(manhwa_id, source)` rows reduced to zero.
