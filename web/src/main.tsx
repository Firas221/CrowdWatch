import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './i18n';
import { App } from './App';

const queries = new QueryClient({
  defaultOptions: {
    // keep showing the last good figures while the server does not answer
    queries: { retry: 1, staleTime: 1000, refetchOnWindowFocus: true, placeholderData: (previous: unknown) => previous },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queries}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
);
