import { useEffect, useMemo, useState } from "react";
import { fetchTrending } from "./api";
import { MetricType, TrendingManhwa } from "./types";

const ASURA_SOURCE_ID = 2;
const metricOptions: { label: string; value: MetricType; caption: string }[] = [
  { label: "Followers", value: "FOLLOWERS", caption: "Asura fanbase growth" }
];

const formatter = new Intl.NumberFormat("en-US", { notation: "compact" });

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Unknown";
  return date.toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function getInitials(title: string) {
  return title
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? "")
    .join("");
}

export default function App() {
  const [metric, setMetric] = useState<MetricType>("FOLLOWERS");
  const [items, setItems] = useState<TrendingManhwa[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    fetchTrending(metric, 10, ASURA_SOURCE_ID)
      .then((data) => {
        if (!cancelled) {
          setItems(data);
          setLoading(false);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [metric]);

  const activeMetric = useMemo(
    () => metricOptions.find((option) => option.value === metric) ?? metricOptions[0],
    [metric]
  );

  return (
    <div className="app">
      <header className="hero">
        <div className="hero-tag">Manhwa Trend Tracker</div>
        <h1>Top 10 movers right now.</h1>
        <p>
          Real-time heat, pure Asura energy. Track the fastest-rising series and see who is surging
          right now.
        </p>
        <div className="hero-controls">
          {metricOptions.map((option) => (
            <button
              key={option.value}
              className="metric-pill is-active"
              onClick={() => setMetric(option.value)}
            >
              <span>{option.label}</span>
              <small>{option.caption}</small>
            </button>
          ))}
        </div>
      </header>

      <main className="panel">
        <div className="panel-header">
          <div>
            <h2>{activeMetric.label} Growth</h2>
            <p>Fuel your next binge with the biggest momentum spikes.</p>
          </div>
          <div className="panel-meta">
            {loading ? "Refreshing…" : `${items.length} series`}
          </div>
        </div>

        {error ? (
          <div className="empty-state">
            <h3>Could not load leaderboard</h3>
            <p>{error}</p>
          </div>
        ) : loading ? (
          <div className="loader">
            <div className="pulse" />
            <p>Loading trending data…</p>
          </div>
        ) : items.length === 0 ? (
          <div className="empty-state">
            <h3>No snapshots yet</h3>
            <p>Run a scraper batch to populate the metric snapshots.</p>
          </div>
        ) : (
          <div className="trend-list">
            {items.map((item, index) => (
              <article className="trend-card" key={item.manhwaId} style={{ animationDelay: `${index * 60}ms` }}>
                <div className="rank">#{index + 1}</div>
                <div className="cover">
                  {item.coverImageUrl ? (
                    <img src={item.coverImageUrl} alt={`${item.title} cover`} loading="lazy" />
                  ) : (
                    <div className="cover-fallback">{getInitials(item.title)}</div>
                  )}
                </div>
                <div className="trend-main">
                  <h3>{item.title}</h3>
                  <p>
                    Latest: {formatter.format(item.latestValue)} · Baseline: {formatter.format(item.previousValue)}
                  </p>
                </div>
                <div className="trend-growth">
                  <span className="label">Growth</span>
                  <span className="value">+{formatter.format(item.growth)}</span>
                  <span className="timestamp">{formatDate(item.latestAt)}</span>
                </div>
              </article>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
