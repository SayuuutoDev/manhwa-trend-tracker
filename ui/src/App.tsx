import { useEffect, useMemo, useState, type MouseEvent } from "react";
import { fetchBatchJobs, fetchTrending, startBatchJob, stopBatchJob } from "./api";
import { BatchJob, MetricType, RankingMode, TrendingManhwa } from "./types";
import defaultCover from "./assets/default-cover.svg";

type TrendingPanel = {
  id: string;
  label: string;
  caption: string;
  value: MetricType;
  rankingMode: RankingMode;
  minPreviousValue?: number;
  headline: string;
  description: string;
};

type SourcePanel = {
  id: number;
  label: string;
};

type Page = "trending" | "batches";

const trendingPanels: TrendingPanel[] = [
  {
    id: "velocity",
    label: "Velocity",
    caption: "Fastest daily growth",
    value: "VIEWS",
    rankingMode: "RATE",
    headline: "Growth velocity ranking.",
    description: "Ranks titles by normalized daily growth."
  },
  {
    id: "most_followed",
    label: "Most Followed",
    caption: "Top follower totals",
    value: "FOLLOWERS",
    rankingMode: "TOTAL",
    headline: "Top series by followers.",
    description: "Scale ranking by latest follower count."
  },
  {
    id: "most_liked",
    label: "Most Liked",
    caption: "Top like totals",
    value: "LIKES",
    rankingMode: "TOTAL",
    headline: "Top series by likes.",
    description: "Scale ranking by latest like count."
  },
  {
    id: "most_subscribed",
    label: "Most Subscribed",
    caption: "Top subscriber totals",
    value: "SUBSCRIBERS",
    rankingMode: "TOTAL",
    headline: "Top series by subscribers.",
    description: "Scale ranking by latest subscriber count."
  },
  {
    id: "breakout",
    label: "Most Trending",
    caption: "Breakout percent growth",
    value: "VIEWS",
    rankingMode: "PCT",
    minPreviousValue: 50000,
    headline: "Breakout movers across all sources.",
    description: "Percent-growth ranking with a baseline floor to reduce noise from tiny titles."
  },
  {
    id: "engagement_likes_views",
    label: "Engagement",
    caption: "Likes per view ratio",
    value: "LIKES",
    rankingMode: "ENGAGEMENT",
    headline: "Engagement ranking by likes/view.",
    description: "Ranks titles by latest likes-to-views efficiency."
  },
  {
    id: "engagement_subs_views",
    label: "Retention Intent",
    caption: "Subscribers per view ratio",
    value: "SUBSCRIBERS",
    rankingMode: "ENGAGEMENT",
    headline: "Retention ranking by subscribers/view.",
    description: "Ranks titles by latest subscribers-to-views efficiency."
  },
  {
    id: "acceleration",
    label: "Acceleration",
    caption: "Growth rate speeding up",
    value: "VIEWS",
    rankingMode: "ACCELERATION",
    headline: "Acceleration ranking, live.",
    description: "Ranks titles by change in growth-per-day between recent and prior windows."
  }
];

const sourcePanels: SourcePanel[] = [
  { id: 1, label: "Webtoons" },
  { id: 2, label: "Asura" },
  { id: 3, label: "Tapas" }
];

function isPanelSupportedBySource(panelId: string, sourceId: number) {
  if (sourceId === 2 && panelId === "most_subscribed") {
    return false;
  }
  if (sourceId !== 2 && panelId === "most_followed") {
    return false;
  }
  if (panelId === "most_liked" || panelId === "engagement_likes_views" || panelId === "engagement_subs_views") {
    return sourceId === 3;
  }
  if (panelId === "most_followed" || panelId === "most_subscribed") {
    return sourceId === 2 || sourceId === 3;
  }
  return true;
}

function resolveMetricForPanel(panel: TrendingPanel, sourceId: number): MetricType {
  if (panel.id === "most_followed" || panel.id === "most_subscribed") {
    return sourceId === 2 ? "FOLLOWERS" : "SUBSCRIBERS";
  }
  if (panel.id === "velocity" || panel.id === "breakout" || panel.id === "acceleration") {
    return sourceId === 2 ? "FOLLOWERS" : "VIEWS";
  }
  return panel.value;
}

function resolveMinPreviousForPanel(panel: TrendingPanel, sourceId: number) {
  if (panel.id === "breakout" && sourceId === 2) {
    return 5000;
  }
  return panel.minPreviousValue;
}

const jobOrder = ["asuraScrapeJob", "webtoonsScrapeJob", "tapasScrapeJob"];
const badges = ["Crown", "Hot", "Rising"];
const formatter = new Intl.NumberFormat("en-US", { notation: "compact" });

function pageFromPath(pathname: string): Page {
  return pathname === "/batches" ? "batches" : "trending";
}

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

function rankingLabel(mode: RankingMode) {
  if (mode === "ABS") return "Absolute growth";
  if (mode === "RATE") return "Growth per day";
  if (mode === "PCT") return "Percent growth";
  if (mode === "TOTAL") return "Total count";
  if (mode === "ENGAGEMENT") return "Engagement ratio";
  return "Acceleration";
}

function rankingDescription(mode: RankingMode) {
  if (mode === "ABS") {
    return "Ranked by raw snapshot growth over the baseline window.";
  }
  if (mode === "RATE") {
    return "Ranked by normalized daily growth using the nearest available one-week baseline.";
  }
  if (mode === "PCT") {
    return "Ranked by relative percentage change versus the baseline snapshot.";
  }
  if (mode === "TOTAL") {
    return "Ranked by latest total metric value.";
  }
  if (mode === "ENGAGEMENT") {
    return "Ranked by ratio between the selected metric and views.";
  }
  return "Ranked by acceleration: recent growth/day minus previous growth/day.";
}

function formatRankingValue(item: TrendingManhwa, panel: TrendingPanel) {
  const score = item.rankingScore;
  if (score == null || !Number.isFinite(score)) {
    return "-";
  }
  if (panel.rankingMode === "PCT" || panel.rankingMode === "ENGAGEMENT") {
    return `${(score * 100).toFixed(1)}%`;
  }
  if (panel.rankingMode === "TOTAL") {
    return formatter.format(score);
  }
  const prefix = score > 0 ? "+" : "";
  if (panel.rankingMode === "RATE") {
    return `${prefix}${formatter.format(score)}/day`;
  }
  if (panel.rankingMode === "ACCELERATION") {
    return `${prefix}${formatter.format(score)}/day²`;
  }
  return `${prefix}${formatter.format(score)}`;
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

function applyTilt(e: MouseEvent<HTMLElement>) {
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

function resetTilt(e: MouseEvent<HTMLElement>) {
  const card = e.currentTarget;
  card.style.setProperty("--tilt-x", "0deg");
  card.style.setProperty("--tilt-y", "0deg");
  card.removeAttribute("data-tilt");
}

function canAnimate() {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return true;
  }
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const finePointer = window.matchMedia("(pointer: fine)").matches;
  return !reducedMotion && finePointer;
}

export default function App() {
  const [page, setPage] = useState<Page>(() => pageFromPath(window.location.pathname));

  const [activePanelId, setActivePanelId] = useState<TrendingPanel["id"]>("velocity");
  const [activeSourceId, setActiveSourceId] = useState<number>(1);
  const [items, setItems] = useState<TrendingManhwa[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retrySeed, setRetrySeed] = useState(0);
  const [lastTrendingSync, setLastTrendingSync] = useState<string | null>(null);

  const [jobs, setJobs] = useState<BatchJob[]>([]);
  const [jobsLoading, setJobsLoading] = useState(true);
  const [jobsError, setJobsError] = useState<string | null>(null);
  const [startingJob, setStartingJob] = useState<string | null>(null);
  const [stoppingJob, setStoppingJob] = useState<string | null>(null);
  const [lastJobsSync, setLastJobsSync] = useState<string | null>(null);

  const [motionEnabled, setMotionEnabled] = useState(canAnimate());

  useEffect(() => {
    const handlePopState = () => setPage(pageFromPath(window.location.pathname));
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
      return;
    }
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setMotionEnabled(canAnimate());
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  useEffect(() => {
    if (page !== "batches") {
      return;
    }

    let cancelled = false;

    async function loadJobs(isSilent: boolean) {
      const controller = new AbortController();
      try {
        if (!isSilent) {
          setJobsLoading(true);
        }
        const data = await fetchBatchJobs(controller.signal);
        if (!cancelled) {
          setJobs(data);
          setJobsError(null);
          setJobsLoading(false);
          setLastJobsSync(new Date().toISOString());
        }
      } catch (err) {
        if (controller.signal.aborted) {
          return;
        }
        if (!cancelled) {
          setJobsError((err as Error).message);
          setJobsLoading(false);
        }
      }
    }

    void loadJobs(false);
    const intervalId = window.setInterval(() => {
      void loadJobs(true);
    }, 3000);

    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, [page, retrySeed]);

  const visiblePanels = useMemo(
    () => trendingPanels.filter((panel) => isPanelSupportedBySource(panel.id, activeSourceId)),
    [activeSourceId]
  );

  useEffect(() => {
    if (visiblePanels.length === 0) {
      return;
    }
    if (!visiblePanels.some((panel) => panel.id === activePanelId)) {
      setActivePanelId(visiblePanels[0].id);
    }
  }, [activePanelId, visiblePanels]);

  useEffect(() => {
    if (page !== "trending" || visiblePanels.length === 0) {
      return;
    }

    const activePanel = visiblePanels.find((option) => option.id === activePanelId) ?? visiblePanels[0];
    const metric = resolveMetricForPanel(activePanel, activeSourceId);
    const minPreviousValue = resolveMinPreviousForPanel(activePanel, activeSourceId);
    let cancelled = false;

    async function loadTrending(isSilent: boolean) {
      const controller = new AbortController();
      try {
        if (!isSilent) {
          setLoading(true);
        }
        setError(null);
        const data = await fetchTrending(
          metric,
          10,
          activeSourceId,
          activePanel.rankingMode,
          minPreviousValue,
          controller.signal
        );
        if (!cancelled) {
          setItems(data);
          setLoading(false);
          setLastTrendingSync(new Date().toISOString());
        }
      } catch (err) {
        if (controller.signal.aborted) {
          return;
        }
        if (!cancelled) {
          setError((err as Error).message);
          setLoading(false);
        }
      }
    }

    void loadTrending(false);
    const intervalId = window.setInterval(() => {
      void loadTrending(true);
    }, 60000);

    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, [activePanelId, activeSourceId, retrySeed, page, visiblePanels]);

  const activePanel = useMemo(
    () => visiblePanels.find((option) => option.id === activePanelId) ?? visiblePanels[0] ?? trendingPanels[0],
    [activePanelId, visiblePanels]
  );
  const activeSource = useMemo(
    () => sourcePanels.find((option) => option.id === activeSourceId) ?? sourcePanels[0],
    [activeSourceId]
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
      setLastJobsSync(new Date().toISOString());
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
      setLastJobsSync(new Date().toISOString());
    } catch (err) {
      setJobsError((err as Error).message);
    } finally {
      setStoppingJob(null);
    }
  }

  function onCardMove(e: MouseEvent<HTMLElement>) {
    if (!motionEnabled) {
      return;
    }
    applyTilt(e);
  }

  function onCardLeave(e: MouseEvent<HTMLElement>) {
    if (!motionEnabled) {
      return;
    }
    resetTilt(e);
  }

  return (
    <div className="app">
      <header className="hero">
        <div className="hero-tag">Manhwa Trend Tracker</div>

        <nav className="top-nav" aria-label="Pages">
          <button
            className={`top-nav-link ${page === "trending" ? "is-active" : ""}`}
            onClick={() => navigate("trending")}
            aria-current={page === "trending" ? "page" : undefined}
          >
            Trending
          </button>
          <button
            className={`top-nav-link ${page === "batches" ? "is-active" : ""}`}
            onClick={() => navigate("batches")}
            aria-current={page === "batches" ? "page" : undefined}
          >
            Batch Runner
          </button>
        </nav>

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
            <h1>{activeSource.label}: {activePanel.headline}</h1>
            <p>{activePanel.description}</p>
            <div className="hero-controls" role="tablist" aria-label="Source panels">
              {sourcePanels.map((option) => (
                <button
                  key={option.id}
                  className={`metric-pill ${option.id === activeSourceId ? "is-active" : ""}`}
                  onClick={() => setActiveSourceId(option.id)}
                  role="tab"
                  aria-selected={option.id === activeSourceId}
                >
                  <span>{option.label}</span>
                  <small>Source scope</small>
                </button>
              ))}
            </div>
            <div className="hero-controls" role="tablist" aria-label="Trending panels">
              {visiblePanels.map((option) => (
                <button
                  key={option.id}
                  className={`metric-pill ${option.id === activePanelId ? "is-active" : ""}`}
                  onClick={() => setActivePanelId(option.id)}
                  role="tab"
                  aria-selected={option.id === activePanelId}
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
        <main className="jobs-panel" aria-live="polite">
          <div className="panel-header">
            <div>
              <h2>Scraper Control</h2>
              <p>Start jobs manually and monitor execution in real time.</p>
            </div>
            <div className="panel-actions">
              <button className="ghost-button" onClick={() => setRetrySeed((value) => value + 1)}>
                Refresh now
              </button>
              <div className="panel-meta">{jobsLoading ? "Refreshing..." : `Last sync ${formatDate(lastJobsSync)}`}</div>
            </div>
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
                    {job.executionId != null ? ` | Execution #${job.executionId}` : ""}
                  </p>

                  <div className="job-actions">
                    <button
                      className="job-run"
                      onClick={() => onStartJob(job.jobName)}
                      disabled={jobsLoading || startingJob === job.jobName || stoppingJob === job.jobName || running}
                    >
                      {startingJob === job.jobName ? "Starting..." : running ? "Running" : "Run job"}
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
                      {stoppingJob === job.jobName || stopping ? "Stopping..." : "Stop job"}
                    </button>
                  </div>
                </article>
              );
            })}
          </div>
        </main>
      ) : (
        <main className="panel" aria-live="polite">
          <div className="panel-header">
            <div>
              <h2>{activePanel.label} Ranking</h2>
              <p>
                {rankingDescription(activePanel.rankingMode)}
                {averageBaselineDays != null
                  ? ` Current board average: ${averageBaselineDays.toFixed(1)} days between snapshots.`
                  : " Current board average: waiting for enough snapshots."}
              </p>
            </div>
            <div className="panel-actions">
              <button className="ghost-button" onClick={() => setRetrySeed((value) => value + 1)}>
                Refresh now
              </button>
              <div className="panel-meta">{loading ? "Refreshing..." : `Last sync ${formatDate(lastTrendingSync)}`}</div>
            </div>
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
              <p>Loading trending data...</p>
            </div>
          ) : items.length === 0 ? (
            <div className="empty-state">
              <h3>No snapshots yet</h3>
              <p>Run a scraper batch to populate the metric snapshots.</p>
            </div>
          ) : (
            <div className="trend-list">
              {items.map((item, index) => {
                const primaryText = formatRankingValue(item, activePanel);
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
                        Latest: {formatter.format(item.latestValue)}
                        {item.previousValue != null ? ` | Baseline: ${formatter.format(item.previousValue)}` : ""}
                      </p>
                    </div>
                    <div className="trend-growth">
                      <span className="label">{rankingLabel(activePanel.rankingMode)}</span>
                      <span className="value">{primaryText}</span>
                      <span className="timestamp">
                        {activePanel.rankingMode === "ENGAGEMENT"
                          ? `${formatter.format(item.latestValue)} / ${formatter.format(item.previousValue ?? 0)}`
                          : Number.isFinite(item.growthPerDay)
                            ? `+${formatter.format(item.growthPerDay)}/day`
                            : "-"}
                        {item.growthPercent != null ? ` | ${(item.growthPercent * 100).toFixed(1)}%` : ""}
                      </span>
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
                      onMouseMove={onCardMove}
                      onMouseLeave={onCardLeave}
                    >
                      {cardContent}
                    </a>
                  );
                }

                return (
                  <article className="trend-card" key={item.manhwaId} onMouseMove={onCardMove} onMouseLeave={onCardLeave}>
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
