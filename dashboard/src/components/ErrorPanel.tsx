export interface ErrorPanelProps {
  readonly title: string;
  readonly message: string;
  /** Shown only when the server said the request is worth retrying. */
  readonly retryable?: boolean | undefined;
  readonly onRetry?: (() => void) | undefined;
}

/**
 * A failed request.
 *
 * The retry button appears only when the error is actually retryable, which the
 * gateway states explicitly in every error body. Offering retry on a 400 trains
 * people to click it on errors where it cannot help, and then to ignore it on
 * the ones where it can.
 */
export function ErrorPanel({ title, message, retryable = false, onRetry }: ErrorPanelProps) {
  return (
    <div className="error-panel" role="alert">
      <div className="error-title">{title}</div>
      <p className="error-message">{message}</p>
      {retryable && onRetry !== undefined && (
        <button type="button" className="error-retry" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}
