/**
 * Wire types for the gateway usage API.
 *
 * Hand-written to mirror `docs/api/openapi.yaml`, NOT generated. A generator
 * would be better at scale; at two endpoints the generated output is larger
 * than this file and the generation step is one more thing to keep running.
 *
 * The field names are snake_case because that is what the server sends. They
 * are deliberately NOT renamed to camelCase at the boundary: a rename means
 * every field exists under two names in the codebase, and the next person has
 * to know which layer they are in to know which to use.
 */

/** GET /v1/usage/summary */
export interface UsageSummary {
  readonly tenant_id: string;
  readonly from: string;
  readonly to: string;
  /** The window actually used, after server-side clamping. May be < requested. */
  readonly window_hours: number;
  readonly requests: number;
  readonly cache_hits: number;
  /** A FRACTION in [0,1], not a percentage. The client formats it. */
  readonly hit_ratio: number;
  readonly prompt_tokens: number;
  readonly completion_tokens: number;
  readonly total_tokens: number;
  readonly upstream_tokens: number;
  readonly tokens_saved: number;
  /**
   * ABSENT when the deployment has no per-token cost - a self-hosted model, for
   * instance. Optional rather than zero, so the UI can hide the tile instead of
   * rendering a confident $0.00.
   */
  readonly estimated_saving_usd?: number;
}

/** One hour. */
export interface UsagePoint {
  readonly hour: string;
  readonly requests: number;
  readonly cache_hits: number;
  readonly hit_ratio: number;
  readonly total_tokens: number;
  readonly upstream_tokens: number;
  readonly tokens_saved: number;
}

/** GET /v1/usage/series */
export interface UsageSeries {
  readonly tenant_id: string;
  readonly window_hours: number;
  /** OLDEST FIRST, so a chart can plot without sorting. */
  readonly points: readonly UsagePoint[];
}

/** The gateway error envelope. */
export interface GatewayErrorBody {
  readonly error: {
    readonly message: string;
    readonly type: string;
    readonly code: string;
    /**
     * Whether resending can succeed.
     *
     * Sent explicitly because inferring it from the status is wrong in both
     * directions: 402 is a 4xx that never succeeds on retry, and 503 is a 5xx
     * that usually does.
     */
    readonly retryable: boolean;
  };
}

/** Windows the picker offers. */
export const WINDOW_OPTIONS = [
  { hours: 1, label: '1h' },
  { hours: 6, label: '6h' },
  { hours: 24, label: '24h' },
  { hours: 168, label: '7d' },
  { hours: 720, label: '30d' },
] as const;

export type WindowHours = (typeof WINDOW_OPTIONS)[number]['hours'];
