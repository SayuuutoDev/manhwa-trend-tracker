export type MetricType = "VIEWS" | "FOLLOWERS" | "SUBSCRIBERS" | "LIKES";
export type RankingMode = "ABS" | "RATE" | "PCT" | "TOTAL" | "ENGAGEMENT" | "ACCELERATION";

export type TrendingManhwa = {
  manhwaId: number;
  title: string;
  metricType: MetricType;
  coverImageUrl?: string | null;
  readUrl?: string | null;
  latestValue: number;
  latestAt: string;
  previousValue: number;
  previousAt: string;
  growth: number;
  baselineDays: number;
  growthPerDay: number;
  growthPercent?: number | null;
  rankingScore?: number | null;
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
