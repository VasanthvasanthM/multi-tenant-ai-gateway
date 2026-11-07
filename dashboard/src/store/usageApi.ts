import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';

import { API_BASE, authHeaders } from '../api/client';
import type { UsageSeries, UsageSummary } from '../api/types';

/**
 * The two usage endpoints.
 *
 * RTK Query rather than useEffect-and-fetch. Not for the caching - two
 * endpoints barely need it - but because it removes the four states every
 * hand-rolled fetch hook gets wrong: a request that resolves after the
 * component unmounts, two windows selected in quick succession resolving out of
 * order, a refetch that blanks the screen while it is in flight, and an error
 * that is replaced by a stale success on the next poll.
 */
export const usageApi = createApi({
  reducerPath: 'usageApi',

  baseQuery: fetchBaseQuery({
    baseUrl: API_BASE,

    // Resolves the global `fetch` on every CALL rather than capturing it when
    // the base query is built.
    //
    // fetchBaseQuery otherwise captures it at creation time, which happens when
    // this module is first imported - before any test has had a chance to
    // install a stub. Every integration test then hit the real network and
    // rendered an error panel. Identical behaviour in the browser; the
    // indirection exists purely so the thing can be tested.
    fetchFn: (...args) => globalThis.fetch(...args),

    prepareHeaders: (headers) => {
      for (const [name, value] of Object.entries(authHeaders())) {
        headers.set(name, value);
      }
      headers.set('Accept', 'application/json');
      return headers;
    },
  }),

  // One tag per window, so changing the window does not invalidate the data for
  // the window the user might switch back to.
  tagTypes: ['Usage'],

  endpoints: (builder) => ({
    summary: builder.query<UsageSummary, number>({
      query: (hours) => `/v1/usage/summary?hours=${hours}`,
      providesTags: (_result, _error, hours) => [{ type: 'Usage', id: `summary-${hours}` }],
    }),

    series: builder.query<UsageSeries, number>({
      query: (hours) => `/v1/usage/series?hours=${hours}`,
      providesTags: (_result, _error, hours) => [{ type: 'Usage', id: `series-${hours}` }],
    }),
  }),
});

export const { useSummaryQuery, useSeriesQuery } = usageApi;
