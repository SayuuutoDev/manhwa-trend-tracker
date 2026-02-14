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
docker compose up -d
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

## Scraping
Run Asura scrape once (also fills cover/description/genre):
```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=scrape -Dspring-boot.run.arguments="--app.asura.run-once=true --app.scrape.enabled=false"
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
- `http://localhost:5173`

## Troubleshooting
If Maven fails with `Permission denied` in `target/`, fix ownership:
```bash
sudo chown -R $USER:$USER target
```
Or remove and rebuild:
```bash
sudo rm -rf target
```
