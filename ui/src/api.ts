import { BatchJob, BatchStartResponse, MetricType, RankingMode, TrendingManhwa } from "./types";

async function parseApiError(response: Response): Promise<string> {
  let message = `Request failed (${response.status})`;
  try {
    const body = await response.json();
    if (body?.message) {
      message = body.message;
    }
  } catch (_) {
    // ignore parse errors
  }
  return message;
}

export async function fetchTrending(
  metric: MetricType,
  limit = 10,
  sourceId?: number,
  mode: RankingMode = "RATE"
): Promise<TrendingManhwa[]> {
  const params = new URLSearchParams({ metric, limit: String(limit), mode });
  if (sourceId !== undefined) {
    params.set("sourceId", String(sourceId));
  }
  const response = await fetch(`/api/trending?${params.toString()}`);
  if (!response.ok) {
    throw new Error(await parseApiError(response));
  }
  return response.json();
}

export async function fetchBatchJobs(): Promise<BatchJob[]> {
  const response = await fetch("/api/batches");
  if (!response.ok) {
    throw new Error(await parseApiError(response));
  }
  return response.json();
}

export async function startBatchJob(jobName: string): Promise<BatchStartResponse> {
  const response = await fetch(`/api/batches/${encodeURIComponent(jobName)}/start`, {
    method: "POST"
  });
  if (!response.ok) {
    throw new Error(await parseApiError(response));
  }
  return response.json();
}

export async function stopBatchJob(jobName: string): Promise<BatchStartResponse> {
  const response = await fetch(`/api/batches/${encodeURIComponent(jobName)}/stop`, {
    method: "POST"
  });
  if (!response.ok) {
    throw new Error(await parseApiError(response));
  }
  return response.json();
}
