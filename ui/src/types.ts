export type MetricType = "VIEWS" | "FOLLOWERS" | "SUBSCRIBERS" | "LIKES";

export type TrendingManhwa = {
  manhwaId: number;
  title: string;
  metricType: MetricType;
  coverImageUrl?: string | null;
  latestValue: number;
  latestAt: string;
  previousValue: number;
  previousAt: string;
  growth: number;
};
