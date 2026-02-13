import { MetricType, TrendingManhwa } from "./types";

export async function fetchTrending(
  metric: MetricType,
  limit = 10,
  sourceId?: number
): Promise<TrendingManhwa[]> {
  const params = new URLSearchParams({ metric, limit: String(limit) });
  if (sourceId !== undefined) {
    params.set("sourceId", String(sourceId));
  }
  const response = await fetch(`/api/trending?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to load trending data (${response.status})`);
  }
  return response.json();
}
