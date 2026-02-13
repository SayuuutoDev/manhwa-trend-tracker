import { useEffect, useMemo, useState } from "react";
import { fetchTrending } from "./api";
import { MetricType, TrendingManhwa } from "./types";

const ASURA_SOURCE_ID = 2;
const metricOptions: { label: string; value: MetricType; caption: string }[] = [
  { label: "Asura Pulse", value: "FOLLOWERS", caption: "Followers surging right now" }
];

const formatter = new Intl.NumberFormat("en-US", { notation: "compact" });
const badges = ["Crown", "Hot", "Rising"];

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

function applyTilt(e: React.MouseEvent<HTMLElement>) {
  const card = e.currentTarget;
  const rect = card.getBoundingClientRect();
  const x = e.clientX - rect.left;
  const y = e.clientY - rect.top;
  const centerX = rect.width / 2;
  const centerY = rect.height / 2;
  const rotateX = ((y - centerY) / centerY) * -6;
  const rotateY = ((x - centerX) / centerX) * 6;
  card.style.setProperty("--tilt-x", `${rotateX.toFixed(2)}deg`);
  card.style.setProperty("--tilt-y", `${rotateY.toFixed(2)}deg`);
  card.style.setProperty("--glow-x", `${(x / rect.width) * 100}%`);
  card.style.setProperty("--glow-y", `${(y / rect.height) * 100}%`);
  card.setAttribute("data-tilt", "true");
}

function resetTilt(e: React.MouseEvent<HTMLElement>) {
  const card = e.currentTarget;
  card.style.setProperty("--tilt-x", "0deg");
  card.style.setProperty("--tilt-y", "0deg");
  card.removeAttribute("data-tilt");
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
        <h1>Asura’s hottest climbs, framed in neon.</h1>
        <p>
          A high-voltage leaderboard designed for instant obsession. Bigger covers, sharper motion,
          and the fastest-rising series at a glance.
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
              <article
                className="trend-card"
                key={item.manhwaId}
                onMouseMove={applyTilt}
                onMouseLeave={resetTilt}
              >
                <div className="rank">#{index + 1}</div>
                <div className="cover">
                  {index < 3 ? <div className={`badge badge-${index}`}>{badges[index]}</div> : null}
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
