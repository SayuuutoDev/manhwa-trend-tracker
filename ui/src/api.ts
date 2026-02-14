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
    let message = `Failed to load trending data (${response.status})`;
    try {
      const body = await response.json();
      if (body?.message) {
        message = body.message;
      }
    } catch (_) {
      // ignore parse errors
    }
    throw new Error(message);
  }
  return response.json();
}
