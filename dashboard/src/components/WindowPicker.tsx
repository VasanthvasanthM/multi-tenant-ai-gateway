import { WINDOW_OPTIONS } from '../api/types';

export interface WindowPickerProps {
  readonly value: number;
  readonly onChange: (hours: number) => void;
  readonly disabled?: boolean | undefined;
}

/**
 * The time window.
 *
 * Radio semantics via `aria-pressed` on buttons rather than a `<select>`: the
 * options are few and switching between them is the main interaction, so a
 * one-click control beats a two-click one.
 *
 * Filters sit in one row ABOVE the charts, which is where a reader looks for
 * them.
 */
export function WindowPicker({ value, onChange, disabled = false }: WindowPickerProps) {
  return (
    <div className="window-picker" role="group" aria-label="Time window">
      {WINDOW_OPTIONS.map((option) => (
        <button
          key={option.hours}
          type="button"
          className="window-option"
          aria-pressed={option.hours === value}
          disabled={disabled}
          onClick={() => onChange(option.hours)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
