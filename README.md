# Manhwa Trend Tracker

This repo has two parts:
- Backend (Spring Boot): scraping + API.
- Frontend (Vite + React): Asura-only trending leaderboard UI.

## Prerequisites
- Java 21+
- Node.js 18+ and npm
- Docker (for PostgreSQL)

## Database
Start PostgreSQL via Docker:
```bash
docker-compose up
```

## Backend (API)
Run the API without scraping (recommended for UI use):
```bash
./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--app.scrape.enabled=false"
```

To enable scraping jobs, use the `scrape` profile:
```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=scrape -Dspring-boot.run.arguments="--app.scrape.enabled=true"
```

API examples:
- Asura-only trending (followers): `http://localhost:8080/api/trending?metric=FOLLOWERS&limit=10&sourceId=2`
- Generic trending (all sources, views): `http://localhost:8080/api/trending?metric=VIEWS&limit=10`
- List batch job status/progress: `http://localhost:8080/api/batches`
- Start a batch job manually: `POST http://localhost:8080/api/batches/asuraScrapeJob/start`

## Scraping
Run Asura scrape once (also fills cover/description/genre):
```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=scrape -Dspring-boot.run.arguments="--app.asura.run-once=true --app.scrape.enabled=false"
```

Control Webtoons scrape depth (top N entries from Popular page):
```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=scrape -Dspring-boot.run.arguments="--app.webtoons.max-items=50 --app.scrape.enabled=false"
```

Run Webtoons + Asura + Tapas once (if you need all sources):
```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=scrape -Dspring-boot.run.arguments="--app.scrape.enabled=true --app.asura.run-once=true --app.tapas.run-once=true"
```

## Frontend (UI)
Install deps:
```bash
cd ui
npm install
```

Run dev server:
```bash
cd ui
npm run dev
```

Open:
- `http://localhost:5173/` (Trending page)
- `http://localhost:5173/batches` (Batch Runner page for running jobs + live progress)

## Social ranking image
To generate a weekly-ready image that mirrors the trending board, call the new API:
```bash
curl \"http://localhost:8080/api/social-ranking.png?metric=VIEWS&mode=RATE&limit=5\" --output weekly-ranking.png
```
Add `sourceId` (1=Webtoons, 2=Asura, 3=Tapas) to limit to a source, or leave empty for all sources. Optional params `title`, `subtitle`, and `includeTimestamp` (true/false) customize the rendered banner.

Render presets are also available on both image and video endpoints:
- `theme=clean|neon|dark`
- `format=tiktok|instagram|x`
- `pace=fast|standard`

Example image preset:
```bash
curl \"http://localhost:8080/api/social-ranking.png?metric=VIEWS&mode=RATE&theme=neon&format=instagram&pace=standard&limit=5\" --output weekly-ranking.png
```

Example video preset:
```bash
curl \"http://localhost:8080/api/social-ranking.mp4?metric=VIEWS&mode=RATE&theme=neon&format=tiktok&pace=fast&sourceId=2\" --output weekly-ranking.mp4
```

Video pacing presets:
- `fast`: 8 seconds total, intro ~0.5s.
- `standard`: 12 seconds total, intro ~0.75s.

### Asura cover caching
The Asura scraper now downloads each series cover into `cover-cache/asura/` and stores the public `/covers/asura/...` URL in `manhwas.cover_image_url`. That local URL is served through the new `/covers/**` handler and is reused by the social image job, avoiding direct requests to the Asura CDN. Tune `app.cover-storage.path` or `app.cover-storage.base-url` (default `http://localhost:8080/covers`) when you want a different storage location or prefix.

## Troubleshooting
If Maven fails with `Permission denied` in `target/`, fix ownership:
```bash
sudo chown -R $USER:$USER target
```
Or remove and rebuild:
```bash
sudo rm -rf target
```
