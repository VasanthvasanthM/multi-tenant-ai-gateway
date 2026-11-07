import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';

import { App } from './App';
import { store } from './store';
import './styles.css';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('no #root element; index.html and main.tsx disagree');
}

createRoot(container).render(
  <StrictMode>
    <Provider store={store}>
      <App />
    </Provider>
  </StrictMode>,
);
