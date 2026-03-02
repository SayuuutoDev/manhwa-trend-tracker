import { useEffect, useMemo, useState, type MouseEvent } from "react";
import { downloadBinary, fetchBatchJobs, fetchSocialQueue, fetchTrending, startBatchJob, stopBatchJob } from "./api";
import { BatchJob, MetricType, RankingMode, RankingWindow, SocialQueueItem, TrendingManhwa } from "./types";
import defaultCover from "./assets/default-cover.svg";

type TrendingPanel = {
  id: string;
  label: string;
  caption: string;
  value: MetricType;
  rankingMode: RankingMode;
  genre?: string;
  minPreviousValue?: number;
  headline: string;
  description: string;
};

type SourcePanel = {
  id: number;
  label: string;
};

type WindowPanel = {
  id: RankingWindow;
  label: string;
};

type Page = "trending" | "batches" | "social";
type SocialFormat = "tiktok" | "instagram" | "reels" | "x";
type SocialPace = "fast" | "standard";
type SocialIntensity = "calm" | "standard" | "hype";
type SocialTheme = "clean" | "neon" | "dark";
type SocialVariant = "auto" | "A" | "B";

const trendingPanels: TrendingPanel[] = [
  {
    id: "velocity",
    label: "Velocity",
    caption: "Fastest daily growth",
    value: "VIEWS",
    rankingMode: "RATE",
    headline: "Fastest rising titles.",
    description: "Ranks titles by normalized daily growth."
  },
  {
    id: "biggest_gainers",
    label: "Biggest Gainers",
    caption: "Largest net growth",
    value: "VIEWS",
    rankingMode: "ABS",
    headline: "Largest net gains this window.",
    description: "Ranks titles by absolute growth for stable weekly authority boards."
  },
  {
    id: "most_followed",
    label: "Most Followed",
    caption: "Top follower totals",
    value: "FOLLOWERS",
    rankingMode: "TOTAL",
    headline: "Top series by followers.",
    description: "Scales ranking by latest follower count."
  },
  {
    id: "most_liked",
    label: "Most Liked",
    caption: "Top like totals",
    value: "LIKES",
    rankingMode: "TOTAL",
    headline: "Top series by likes.",
    description: "Scales ranking by latest like count."
  },
  {
    id: "most_subscribed",
    label: "Most Subscribed",
    caption: "Top subscriber totals",
    value: "SUBSCRIBERS",
    rankingMode: "TOTAL",
    headline: "Top series by subscribers.",
    description: "Scales ranking by latest subscriber count."
  },
  {
    id: "breakout",
    label: "Breakout",
    caption: "Breakout percent growth",
    value: "VIEWS",
    rankingMode: "PCT",
    headline: "Breakout movers across all sources.",
    description: "Percent-growth ranking with a baseline floor to reduce noise from tiny titles."
  },
  {
    id: "engagement",
    label: "Engagement",
    caption: "Likes per view ratio",
    value: "LIKES",
    rankingMode: "ENGAGEMENT",
    headline: "Engagement ranking by likes/view.",
    description: "Ranks titles by latest likes-to-views efficiency."
  },
  {
    id: "heating_up",
    label: "Heating Up Fast",
    caption: "Growth rate speeding up",
    value: "VIEWS",
    rankingMode: "ACCELERATION",
    headline: "Momentum acceleration ranking, live.",
    description: "Ranks titles by change in growth-per-day between recent and prior windows."
  },
  {
    id: "social_rank",
    label: "Social Rank",
    caption: "Composite social score",
    value: "VIEWS",
    rankingMode: "SOCIAL",
    headline: "Editor-style social composite.",
    description: "Ranks titles by a weighted blend of velocity, absolute growth, and breakout signal."
  }
];

const sourcePanels: SourcePanel[] = [
  { id: 1, label: "Webtoons" },
  { id: 2, label: "Asura" },
  { id: 3, label: "Tapas" }
];

const windowPanels: WindowPanel[] = [
  { id: "DAILY", label: "Daily" },
  { id: "WEEKLY", label: "Weekly" }
];

const socialModeOptions: RankingMode[] = ["RATE", "PCT", "ABS", "TOTAL", "ENGAGEMENT", "ACCELERATION", "SOCIAL"];
const socialMetricOptions: MetricType[] = ["VIEWS", "FOLLOWERS", "SUBSCRIBERS", "LIKES"];

function isPanelSupportedBySource(panelId: string, sourceId: number) {
  if (sourceId === 2 && panelId === "most_subscribed") {
    return false;
  }
  if (sourceId !== 2 && panelId === "most_followed") {
    return false;
  }
  if (panelId === "most_liked" || panelId === "engagement") {
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
  if (
    panel.id === "velocity" ||
    panel.id === "breakout" ||
    panel.id === "heating_up" ||
    panel.id === "biggest_gainers" ||
    panel.id === "social_rank"
  ) {
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
  if (pathname === "/batches") return "batches";
  if (pathname === "/social") return "social";
  return "trending";
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
  if (mode === "ACCELERATION") return "Heating-up score";
  return "Social composite";
}

function rankingDescription(mode: RankingMode, window: RankingWindow) {
  const windowText = window === "DAILY" ? "daily" : "weekly";
  if (mode === "ABS") {
    return `Ranked by raw ${windowText} growth over the baseline window.`;
  }
  if (mode === "RATE") {
    return `Ranked by normalized ${windowText} growth using the nearest available baseline snapshot.`;
  }
  if (mode === "PCT") {
    return `Ranked by relative ${windowText} percentage change versus the baseline snapshot.`;
  }
  if (mode === "TOTAL") {
    return `Ranked by latest total metric value for the ${windowText} board.`;
  }
  if (mode === "ENGAGEMENT") {
    return "Ranked by ratio between the selected metric and views.";
  }
  if (mode === "ACCELERATION") {
    return "Ranked by momentum acceleration: recent growth/day minus previous growth/day.";
  }
  return "Ranked by composite social score blending velocity, absolute growth, and breakout signal.";
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
  if (panel.rankingMode === "SOCIAL") {
    return score.toFixed(3);
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

function confidenceText(item: TrendingManhwa) {
  if (item.confidenceLabel && item.confidenceScore != null) {
    return `${item.confidenceLabel} confidence (${Math.round(item.confidenceScore * 100)}%)`;
  }
  return "Confidence pending";
}

function sanitizeFilename(value: string) {
  return value.replace(/[^a-z0-9-_]+/gi, "-").replace(/-+/g, "-").replace(/^-|-$/g, "").toLowerCase();
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
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
  const [activeWindow, setActiveWindow] = useState<RankingWindow>("DAILY");
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

  const [socialMetric, setSocialMetric] = useState<MetricType>("VIEWS");
  const [socialMode, setSocialMode] = useState<RankingMode>("RATE");
  const [socialWindow, setSocialWindow] = useState<RankingWindow>("DAILY");
  const [socialSourceId, setSocialSourceId] = useState<number | "all">("all");
  const [socialLimit, setSocialLimit] = useState<number>(4);
  const [socialTheme, setSocialTheme] = useState<SocialTheme>("neon");
  const [socialFormat, setSocialFormat] = useState<SocialFormat>("tiktok");
  const [socialPace, setSocialPace] = useState<SocialPace>("fast");
  const [socialIntensity, setSocialIntensity] = useState<SocialIntensity>("standard");
  const [socialVariant, setSocialVariant] = useState<SocialVariant>("auto");
  const [socialTitle, setSocialTitle] = useState("");
  const [socialSubtitle, setSocialSubtitle] = useState("");
  const [socialCtaText, setSocialCtaText] = useState("Follow for daily movers");
  const [socialHandle, setSocialHandle] = useState("@manhwa.tracker");
  const [socialTag, setSocialTag] = useState("#manhwa");
  const [downloadingType, setDownloadingType] = useState<"png" | "mp4" | "bundle" | null>(null);
  const [socialError, setSocialError] = useState<string | null>(null);
  const [socialMessage, setSocialMessage] = useState<string | null>(null);
  const [queueItems, setQueueItems] = useState<SocialQueueItem[]>([]);
  const [queueLoading, setQueueLoading] = useState(false);
  const [queueError, setQueueError] = useState<string | null>(null);

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

  useEffect(() => {
    if (page !== "social") {
      return;
    }

    let cancelled = false;
    async function loadQueue() {
      setQueueLoading(true);
      try {
        const data = await fetchSocialQueue(socialSourceId === "all" ? undefined : socialSourceId);
        if (!cancelled) {
          setQueueItems(data);
          setQueueError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setQueueError((err as Error).message);
        }
      } finally {
        if (!cancelled) {
          setQueueLoading(false);
        }
      }
    }

    void loadQueue();
    return () => {
      cancelled = true;
    };
  }, [page, socialSourceId]);

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
    const genre = activePanel.genre;
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
          activeWindow,
          genre,
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
  }, [activePanelId, activeSourceId, activeWindow, retrySeed, page, visiblePanels]);

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
      .filter((value): value is number => value != null && Number.isFinite(value) && value > 0);
    if (days.length === 0) {
      return null;
    }
    return days.reduce((sum, value) => sum + value, 0) / days.length;
  }, [items]);

  function navigate(nextPage: Page) {
    const path = nextPage === "batches" ? "/batches" : nextPage === "social" ? "/social" : "/";
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

  function socialParams() {
    const params = new URLSearchParams({
      metric: socialMetric,
      mode: socialMode,
      window: socialWindow,
      limit: String(socialLimit),
      theme: socialTheme,
      format: socialFormat,
      pace: socialPace,
      intensity: socialIntensity,
      ctaHandle: socialHandle,
      ctaText: socialCtaText,
      campaignTag: socialTag
    });
    if (socialSourceId !== "all") {
      params.set("sourceId", String(socialSourceId));
    }
    if (socialTitle.trim()) {
      params.set("title", socialTitle.trim());
    }
    if (socialSubtitle.trim()) {
      params.set("subtitle", socialSubtitle.trim());
    }
    if (socialVariant !== "auto") {
      params.set("variant", socialVariant);
    }
    return params;
  }

  async function onDownload(kind: "png" | "mp4" | "bundle") {
    setSocialError(null);
    setSocialMessage(null);
    setDownloadingType(kind);
    try {
      const endpoint = kind === "png"
        ? "/api/social-ranking.png"
        : kind === "mp4"
          ? "/api/social-ranking.mp4"
          : "/api/social-ranking.bundle";
      const ext = kind === "png" ? "png" : kind === "mp4" ? "mp4" : "zip";
      const params = socialParams();
      const blob = await downloadBinary(`${endpoint}?${params.toString()}`);
      const file = `social-${sanitizeFilename(socialMode)}-${sanitizeFilename(socialWindow)}-${Date.now()}.${ext}`;
      saveBlob(blob, file);
      setSocialMessage(`Downloaded ${file}`);
    } catch (err) {
      setSocialError((err as Error).message);
    } finally {
      setDownloadingType(null);
    }
  }

  async function refreshQueue() {
    setQueueError(null);
    setQueueLoading(true);
    try {
      const data = await fetchSocialQueue(socialSourceId === "all" ? undefined : socialSourceId);
      setQueueItems(data);
    } catch (err) {
      setQueueError((err as Error).message);
    } finally {
      setQueueLoading(false);
    }
  }

  async function onQueueDownload(item: SocialQueueItem) {
    setSocialError(null);
    setSocialMessage(null);
    try {
      const ext = item.endpoint.endsWith(".png")
        ? "png"
        : item.endpoint.endsWith(".mp4")
          ? "mp4"
          : "zip";
      const blob = await downloadBinary(`${item.endpoint}?${item.query}`);
      const file = `${sanitizeFilename(item.id)}-${Date.now()}.${ext}`;
      saveBlob(blob, file);
      setSocialMessage(`Downloaded ${file}`);
    } catch (err) {
      setSocialError((err as Error).message);
    }
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
          <button
            className={`top-nav-link ${page === "social" ? "is-active" : ""}`}
            onClick={() => navigate("social")}
            aria-current={page === "social" ? "page" : undefined}
          >
            Social Media
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
        ) : page === "social" ? (
          <>
            <h1>Social Media Export Studio</h1>
            <p>
              Configure render presets and download PNG, MP4, or full bundle assets for posting.
            </p>
          </>
        ) : (
          <>
            <h1>{activeSource.label} ({activeWindow === "DAILY" ? "Daily" : "Weekly"}): {activePanel.headline}</h1>
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
            <div className="hero-controls" role="tablist" aria-label="Window panels">
              {windowPanels.map((option) => (
                <button
                  key={option.id}
                  className={`metric-pill ${option.id === activeWindow ? "is-active" : ""}`}
                  onClick={() => setActiveWindow(option.id)}
                  role="tab"
                  aria-selected={option.id === activeWindow}
                >
                  <span>{option.label}</span>
                  <small>Ranking window</small>
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
      ) : page === "social" ? (
        <main className="panel social-panel" aria-live="polite">
          <div className="panel-header">
            <div>
              <h2>Social Download Controls</h2>
              <p>Generate assets from presets and download directly.</p>
            </div>
            <div className="panel-actions">
              <button className="ghost-button" onClick={() => void refreshQueue()}>
                Refresh queue
              </button>
              <div className="panel-meta">{queueLoading ? "Loading queue..." : `Queue items ${queueItems.length}`}</div>
            </div>
          </div>

          <div className="social-grid">
            <section className="social-card">
              <h3>Render Setup</h3>
              <div className="social-form-grid">
                <label className="social-field">
                  <span>Source</span>
                  <select value={socialSourceId} onChange={(e) => setSocialSourceId(e.target.value === "all" ? "all" : Number(e.target.value))}>
                    <option value="all">All sources</option>
                    {sourcePanels.map((source) => (
                      <option key={source.id} value={source.id}>{source.label}</option>
                    ))}
                  </select>
                </label>
                <label className="social-field">
                  <span>Metric</span>
                  <select value={socialMetric} onChange={(e) => setSocialMetric(e.target.value as MetricType)}>
                    {socialMetricOptions.map((metric) => (
                      <option key={metric} value={metric}>{metric}</option>
                    ))}
                  </select>
                </label>
                <label className="social-field">
                  <span>Mode</span>
                  <select value={socialMode} onChange={(e) => setSocialMode(e.target.value as RankingMode)}>
                    {socialModeOptions.map((mode) => (
                      <option key={mode} value={mode}>{mode}</option>
                    ))}
                  </select>
                </label>
                <label className="social-field">
                  <span>Window</span>
                  <select value={socialWindow} onChange={(e) => setSocialWindow(e.target.value as RankingWindow)}>
                    <option value="DAILY">DAILY</option>
                    <option value="WEEKLY">WEEKLY</option>
                  </select>
                </label>
                <label className="social-field">
                  <span>Format</span>
                  <select value={socialFormat} onChange={(e) => setSocialFormat(e.target.value as SocialFormat)}>
                    <option value="tiktok">tiktok</option>
                    <option value="instagram">instagram</option>
                    <option value="reels">reels</option>
                    <option value="x">x</option>
                  </select>
                </label>
                <label className="social-field">
                  <span>Theme</span>
                  <select value={socialTheme} onChange={(e) => setSocialTheme(e.target.value as SocialTheme)}>
                    <option value="clean">clean</option>
                    <option value="neon">neon</option>
                    <option value="dark">dark</option>
                  </select>
                </label>
                <label className="social-field">
                  <span>Pace</span>
                  <select value={socialPace} onChange={(e) => setSocialPace(e.target.value as SocialPace)}>
                    <option value="fast">fast</option>
                    <option value="standard">standard</option>
                  </select>
                </label>
                <label className="social-field">
                  <span>Intensity</span>
                  <select value={socialIntensity} onChange={(e) => setSocialIntensity(e.target.value as SocialIntensity)}>
                    <option value="calm">calm</option>
                    <option value="standard">standard</option>
                    <option value="hype">hype</option>
                  </select>
                </label>
                <label className="social-field">
                  <span>Variant</span>
                  <select value={socialVariant} onChange={(e) => setSocialVariant(e.target.value as SocialVariant)}>
                    <option value="auto">auto</option>
                    <option value="A">A</option>
                    <option value="B">B</option>
                  </select>
                </label>
                <label className="social-field">
                  <span>Limit (3-5)</span>
                  <input
                    type="number"
                    min={3}
                    max={5}
                    value={socialLimit}
                    onChange={(e) => setSocialLimit(Math.max(3, Math.min(5, Number(e.target.value) || 3)))}
                  />
                </label>
                <label className="social-field">
                  <span>Title</span>
                  <input
                    type="text"
                    value={socialTitle}
                    onChange={(e) => setSocialTitle(e.target.value)}
                    placeholder="Optional override title"
                  />
                </label>
                <label className="social-field">
                  <span>Subtitle</span>
                  <input
                    type="text"
                    value={socialSubtitle}
                    onChange={(e) => setSocialSubtitle(e.target.value)}
                    placeholder="Optional override subtitle"
                  />
                </label>
                <label className="social-field">
                  <span>CTA Text</span>
                  <input
                    type="text"
                    value={socialCtaText}
                    onChange={(e) => setSocialCtaText(e.target.value)}
                  />
                </label>
                <label className="social-field">
                  <span>Handle</span>
                  <input
                    type="text"
                    value={socialHandle}
                    onChange={(e) => setSocialHandle(e.target.value)}
                  />
                </label>
                <label className="social-field">
                  <span>Tag</span>
                  <input
                    type="text"
                    value={socialTag}
                    onChange={(e) => setSocialTag(e.target.value)}
                  />
                </label>
              </div>
            </section>

            <section className="social-card">
              <h3>Download Assets</h3>
              <div className="social-actions">
                <button className="job-run" onClick={() => void onDownload("png")} disabled={downloadingType != null}>
                  {downloadingType === "png" ? "Preparing PNG..." : "Download PNG"}
                </button>
                <button className="job-run" onClick={() => void onDownload("mp4")} disabled={downloadingType != null}>
                  {downloadingType === "mp4" ? "Preparing MP4..." : "Download MP4"}
                </button>
                <button className="job-run" onClick={() => void onDownload("bundle")} disabled={downloadingType != null}>
                  {downloadingType === "bundle" ? "Preparing Bundle..." : "Download Bundle ZIP"}
                </button>
              </div>
              <p className="job-meta">Uses `/api/social-ranking.png`, `/api/social-ranking.mp4`, and `/api/social-ranking.bundle`.</p>
              {socialMessage ? <p className="social-ok">{socialMessage}</p> : null}
              {socialError ? <p className="jobs-error">{socialError}</p> : null}
            </section>

            <section className="social-card">
              <h3>Quick Queue</h3>
              {queueError ? <p className="jobs-error">{queueError}</p> : null}
              <div className="social-queue">
                {queueItems.map((item) => (
                  <article className="social-queue-item" key={item.id}>
                    <h4>{item.title}</h4>
                    <p>{item.endpoint}</p>
                    <button className="job-stop" onClick={() => void onQueueDownload(item)}>Download</button>
                  </article>
                ))}
              </div>
            </section>
          </div>
        </main>
      ) : (
        <main className="panel" aria-live="polite">
          <div className="panel-header">
            <div>
              <h2>{activePanel.label} Ranking</h2>
              <p>
                {rankingDescription(activePanel.rankingMode, activeWindow)}
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
                            ? `+${formatter.format(item.growthPerDay ?? 0)}/day`
                            : "-"}
                        {item.growthPercent != null ? ` | ${(item.growthPercent * 100).toFixed(1)}%` : ""}
                      </span>
                      <span className="timestamp">{confidenceText(item)}</span>
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
