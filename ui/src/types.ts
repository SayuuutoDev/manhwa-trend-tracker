export type MetricType = "VIEWS" | "FOLLOWERS" | "SUBSCRIBERS" | "LIKES";
export type RankingMode = "ABS" | "RATE" | "PCT" | "TOTAL" | "ENGAGEMENT" | "ACCELERATION" | "SOCIAL";
export type RankingWindow = "DAILY" | "WEEKLY";

export type TrendingManhwa = {
  manhwaId: number;
  title: string;
  genre?: string | null;
  metricType: MetricType;
  coverImageUrl?: string | null;
  readUrl?: string | null;
  latestValue: number;
  latestAt: string | null;
  previousValue: number | null;
  previousAt: string | null;
  growth: number | null;
  baselineDays: number | null;
  growthPerDay: number | null;
  growthPercent?: number | null;
  rankingScore?: number | null;
  confidenceScore?: number | null;
  confidenceLabel?: string | null;
  snapshotAgeHours?: number | null;
  baselineCoverage?: number | null;
  rankingMode: RankingMode;
};

export type BatchJob = {
  jobName: string;
  label: string;
  running: boolean;
  executionId: number | null;
  status: string;
  exitCode: string | null;
  startedAt: string | null;
  endedAt: string | null;
  lastUpdatedAt: string | null;
  readCount: number;
  writeCount: number;
  filterCount: number;
  skipCount: number;
  commitCount: number;
  progressPercent: number | null;
};

export type BatchStartResponse = {
  jobName: string;
  executionId: number;
  message: string;
};

export type SocialQueueItem = {
  id: string;
  title: string;
  endpoint: string;
  query: string;
};
