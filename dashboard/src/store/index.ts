import { configureStore } from '@reduxjs/toolkit';
import { setupListeners } from '@reduxjs/toolkit/query';

import { usageApi } from './usageApi';

export const store = configureStore({
  reducer: {
    [usageApi.reducerPath]: usageApi.reducer,
  },
  middleware: (getDefaultMiddleware) => getDefaultMiddleware().concat(usageApi.middleware),
});

// Refetch when the tab regains focus or the network returns.
//
// A dashboard left open on a second monitor otherwise shows figures from
// whenever it was last looked at, with nothing indicating they are stale - and
// a stale dashboard is worse than an empty one, because it is believed.
setupListeners(store.dispatch);

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
