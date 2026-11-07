import { useEffect, useState } from 'react';

/**
 * Tracks the viewer's colour-scheme preference.
 *
 * Listens for changes rather than reading once at mount: a viewer switching
 * their OS theme with the dashboard open would otherwise keep the chart colours
 * for the old surface, and those were validated against THAT surface - aqua on
 * the wrong background drops below the contrast floor.
 */
export function useDarkMode(): boolean {
  const [dark, setDark] = useState<boolean>(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return false;
    }
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return;
    }
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (event: MediaQueryListEvent): void => setDark(event.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  return dark;
}
