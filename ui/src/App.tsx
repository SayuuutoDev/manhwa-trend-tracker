import { useEffect, useMemo, useState } from "react";
import { fetchBatchJobs, fetchTrending, startBatchJob, stopBatchJob } from "./api";
import { BatchJob, MetricType, RankingMode, TrendingManhwa } from "./types";
import defaultCover from "./assets/default-cover.svg";

type TrendingPanel = {
  id: "asura" | "tapas" | "webtoon";
  label: string;
  caption: string;
  value: MetricType;
  sourceId: number;
  rankingMode: RankingMode;
  headline: string;
  description: string;
};

const trendingPanels: TrendingPanel[] = [
  {
    id: "asura",
    label: "Asura Pulse",
    caption: "Followers surging right now",
    value: "FOLLOWERS",
    sourceId: 2,
    rankingMode: "RATE",
    headline: "Asura's hottest climbs, framed in neon.",
    description:
      "A high-voltage leaderboard designed for instant obsession. Bigger covers, sharper motion, and the fastest-rising series at a glance."
  },
  {
    id: "tapas",
    label: "Tapas Growth",
    caption: "Tapas views climbing fastest",
    value: "VIEWS",
    sourceId: 3,
    rankingMode: "ABS",
    headline: "Tapas.io growth ranking, live.",
    description:
      "Track the fastest-moving Tapas titles by view growth and switch between source leaderboards without leaving the trending screen."
  },
  {
    id: "webtoon",
    label: "Webtoon Growth",
    caption: "Webtoon views climbing fastest",
    value: "VIEWS",
    sourceId: 1,
    rankingMode: "RATE",
    headline: "Webtoon growth ranking, live.",
    description:
      "See which Webtoon titles are accelerating right now using a rate-based growth window close to one week."
  }
];
const jobOrder = ["asuraScrapeJob", "webtoonsScrapeJob", "tapasScrapeJob"];

type Page = "trending" | "batches";

const formatter = new Intl.NumberFormat("en-US", { notation: "compact" });
const badges = ["Crown", "Hot", "Rising"];

function formatDate(value: string | null) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return date.toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function pageFromPath(pathname: string): Page {
  return pathname === "/batches" ? "batches" : "trending";
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

function isJobRunning(job: BatchJob) {
  return job.running || job.status === "STARTED" || job.status === "STARTING";
}

function isJobStopping(job: BatchJob) {
  return job.status === "STOPPING";
}

function canStopJob(job: BatchJob) {
  return isJobRunning(job) && !isJobStopping(job);
}

function progressClass(job: BatchJob) {
  if (isJobRunning(job)) return "progress-fill is-running";
  if (job.status === "COMPLETED") return "progress-fill is-completed";
  if (job.status === "FAILED") return "progress-fill is-failed";
  return "progress-fill";
}

function progressWidth(job: BatchJob) {
  if (isJobRunning(job)) return "35%";
  if (job.progressPercent == null) return "0%";
  return `${Math.max(0, Math.min(100, job.progressPercent))}%`;
}

export default function App() {
  const [page, setPage] = useState<Page>(() => pageFromPath(window.location.pathname));

  const [activePanelId, setActivePanelId] = useState<TrendingPanel["id"]>("asura");
  const [items, setItems] = useState<TrendingManhwa[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retrySeed, setRetrySeed] = useState(0);

  const [jobs, setJobs] = useState<BatchJob[]>([]);
  const [jobsLoading, setJobsLoading] = useState(true);
  const [jobsError, setJobsError] = useState<string | null>(null);
  const [startingJob, setStartingJob] = useState<string | null>(null);
  const [stoppingJob, setStoppingJob] = useState<string | null>(null);

  useEffect(() => {
    const handlePopState = () => setPage(pageFromPath(window.location.pathname));
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  useEffect(() => {
    if (page !== "batches") {
      return;
    }

    let cancelled = false;

    async function loadJobs() {
      try {
        const data = await fetchBatchJobs();
        if (!cancelled) {
          setJobs(data);
          setJobsError(null);
          setJobsLoading(false);
        }
      } catch (err) {
        if (!cancelled) {
          setJobsError((err as Error).message);
          setJobsLoading(false);
        }
      }
    }

    setJobsLoading(true);
    loadJobs();
    const intervalId = window.setInterval(loadJobs, 2000);

    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, [page]);

  useEffect(() => {
    if (page !== "trending") {
      return;
    }

    const activePanel = trendingPanels.find((option) => option.id === activePanelId) ?? trendingPanels[0];
    let cancelled = false;
    setLoading(true);
    setError(null);

    fetchTrending(activePanel.value, 10, activePanel.sourceId, activePanel.rankingMode)
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
  }, [activePanelId, retrySeed, page]);

  const activePanel = useMemo(
    () => trendingPanels.find((option) => option.id === activePanelId) ?? trendingPanels[0],
    [activePanelId]
  );

  const orderedJobs = useMemo(() => {
    return [...jobs].sort((a, b) => {
      const aOrder = jobOrder.indexOf(a.jobName);
      const bOrder = jobOrder.indexOf(b.jobName);
      return (aOrder === -1 ? 99 : aOrder) - (bOrder === -1 ? 99 : bOrder);
    });
  }, [jobs]);

  const averageBaselineDays = useMemo(() => {
    const days = items
      .map((item) => item.baselineDays)
      .filter((value): value is number => Number.isFinite(value) && value > 0);
    if (days.length === 0) {
      return null;
    }
    return days.reduce((sum, value) => sum + value, 0) / days.length;
  }, [items]);

  function navigate(nextPage: Page) {
    const path = nextPage === "batches" ? "/batches" : "/";
    if (window.location.pathname !== path) {
      window.history.pushState({}, "", path);
    }
    setPage(nextPage);
  }

  async function onStartJob(jobName: string) {
    setStartingJob(jobName);
    try {
      await startBatchJob(jobName);
      setJobs(await fetchBatchJobs());
      setJobsError(null);
    } catch (err) {
      setJobsError((err as Error).message);
    } finally {
      setStartingJob(null);
    }
  }

  async function onStopJob(jobName: string) {
    setStoppingJob(jobName);
    try {
      await stopBatchJob(jobName);
      setJobs(await fetchBatchJobs());
      setJobsError(null);
    } catch (err) {
      setJobsError((err as Error).message);
    } finally {
      setStoppingJob(null);
    }
  }

  return (
    <div className="app">
      <header className="hero">
        <div className="hero-tag">Manhwa Trend Tracker</div>
        <div className="top-nav">
          <button
            className={`top-nav-link ${page === "batches" ? "is-active" : ""}`}
            onClick={() => navigate("batches")}
          >
            Batch Runner
          </button>
          <button
            className={`top-nav-link ${page === "trending" ? "is-active" : ""}`}
            onClick={() => navigate("trending")}
          >
            Trending
          </button>
        </div>

        {page === "batches" ? (
          <>
            <h1>Run scraping batches from a dedicated backend control page.</h1>
            <p>
              Trigger Asura, Webtoons, or Tapas jobs on demand and watch execution progress, status,
              and counters in real time.
            </p>
          </>
        ) : (
          <>
            <h1>{activePanel.headline}</h1>
            <p>{activePanel.description}</p>
            <div className="hero-controls">
              {trendingPanels.map((option) => (
                <button
                  key={option.id}
                  className={`metric-pill ${option.id === activePanelId ? "is-active" : ""}`}
                  onClick={() => setActivePanelId(option.id)}
                >
                  <span>{option.label}</span>
                  <small>{option.caption}</small>
                </button>
              ))}
            </div>
          </>
        )}
      </header>

      {page === "batches" ? (
        <main className="jobs-panel">
          <div className="panel-header">
            <div>
              <h2>Scraper Control</h2>
              <p>Start jobs manually and monitor execution in real time.</p>
            </div>
            <div className="panel-meta">{jobsLoading ? "Refreshing…" : `${orderedJobs.length} jobs`}</div>
          </div>

          {jobsError ? <p className="jobs-error">{jobsError}</p> : null}

          <div className="job-grid">
            {orderedJobs.map((job) => {
              const running = isJobRunning(job);
              const stopping = isJobStopping(job);
              return (
                <article className="job-card" key={job.jobName}>
                  <div className="job-row">
                    <h3>{job.label}</h3>
                    <span className={`job-status status-${job.status.toLowerCase()}`}>{job.status}</span>
                  </div>

                  <div className="progress-track" aria-label={`${job.label} progress`}>
                    <div
                      className={progressClass(job)}
                      style={{ width: progressWidth(job) }}
                      data-running={running ? "true" : "false"}
                    />
                  </div>

                  <div className="job-stats">
                    <span>Read {formatter.format(job.readCount)}</span>
                    <span>Write {formatter.format(job.writeCount)}</span>
                    <span>Skip {formatter.format(job.skipCount)}</span>
                  </div>

                  <p className="job-meta">
                    Last update: {formatDate(job.lastUpdatedAt)}
                    {job.executionId != null ? ` · Execution #${job.executionId}` : ""}
                  </p>

                  <div className="job-actions">
                    <button
                      className="job-run"
                      onClick={() => onStartJob(job.jobName)}
                      disabled={jobsLoading || startingJob === job.jobName || stoppingJob === job.jobName || running}
                    >
                      {startingJob === job.jobName ? "Starting…" : running ? "Running" : "Run job"}
                    </button>
                    <button
                      className="job-stop"
                      onClick={() => onStopJob(job.jobName)}
                      disabled={
                        jobsLoading ||
                        stoppingJob === job.jobName ||
                        startingJob === job.jobName ||
                        !canStopJob(job)
                      }
                    >
                      {stoppingJob === job.jobName || stopping ? "Stopping…" : "Stop job"}
                    </button>
                  </div>
                </article>
              );
            })}
          </div>
        </main>
      ) : (
        <main className="panel">
          <div className="panel-header">
            <div>
              <h2>{activePanel.label} Ranking</h2>
              <p>
                Fuel your next binge with the biggest momentum spikes. Ranking is rate-based and
                uses the nearest available window to 7 days.
                {averageBaselineDays != null
                  ? ` Current board average: ${averageBaselineDays.toFixed(1)} days between snapshots.`
                  : " Current board average: waiting for enough snapshots."}
              </p>
            </div>
            <div className="panel-meta">{loading ? "Refreshing…" : `${items.length} series`}</div>
          </div>

          {error ? (
            <div className="empty-state">
              <h3>Could not load leaderboard</h3>
              <p>{error}</p>
              <button className="retry-button" onClick={() => setRetrySeed((v) => v + 1)}>
                Retry
              </button>
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
              {items.map((item, index) => {
                const cardContent = (
                  <>
                    <div className="cover">
                      <div className="rank">#{index + 1}</div>
                      {index < 3 ? <div className={`badge badge-${index}`}>{badges[index]}</div> : null}
                      <div
                        className="cover-image"
                        role="img"
                        aria-label={`${item.title} cover`}
                        style={{ backgroundImage: `url(${item.coverImageUrl || defaultCover})` }}
                      />
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
                  </>
                );

                if (item.readUrl) {
                  return (
                    <a
                      className="trend-card is-link"
                      key={item.manhwaId}
                      href={item.readUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      onMouseMove={applyTilt}
                      onMouseLeave={resetTilt}
                    >
                      {cardContent}
                    </a>
                  );
                }

                return (
                  <article
                    className="trend-card"
                    key={item.manhwaId}
                    onMouseMove={applyTilt}
                    onMouseLeave={resetTilt}
                  >
                    {cardContent}
                  </article>
                );
              })}
            </div>
          )}
        </main>
      )}
    </div>
  );
}
