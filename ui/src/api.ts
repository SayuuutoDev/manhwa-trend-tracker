import {
  BatchJob,
  BatchStartResponse,
  MetricType,
  RankingMode,
  RankingWindow,
  SocialQueueItem,
  TrendingManhwa
} from "./types";

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
  mode: RankingMode = "RATE",
  window: RankingWindow = "WEEKLY",
  genre?: string,
  minPreviousValue?: number,
  signal?: AbortSignal
): Promise<TrendingManhwa[]> {
  const params = new URLSearchParams({ metric, limit: String(limit), mode, window });
  if (sourceId !== undefined) {
    params.set("sourceId", String(sourceId));
  }
  if (genre) {
    params.set("genre", genre);
  }
  if (minPreviousValue !== undefined) {
    params.set("minPreviousValue", String(minPreviousValue));
  }
  const response = await fetch(`/api/trending?${params.toString()}`, { signal });
  if (!response.ok) {
    throw new Error(await parseApiError(response));
  }
  return response.json();
}

export async function fetchBatchJobs(signal?: AbortSignal): Promise<BatchJob[]> {
  const response = await fetch("/api/batches", { signal });
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

export async function fetchSocialQueue(sourceId?: number): Promise<SocialQueueItem[]> {
  const params = new URLSearchParams();
  if (sourceId !== undefined) {
    params.set("sourceId", String(sourceId));
  }
  const suffix = params.toString() ? `?${params.toString()}` : "";
  const response = await fetch(`/api/social-ranking/queue${suffix}`);
  if (!response.ok) {
    throw new Error(await parseApiError(response));
  }
  return response.json();
}

export async function downloadBinary(url: string): Promise<Blob> {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(await parseApiError(response));
  }
  return response.blob();
}
