/*
 * Service worker.
 *
 * Makes the client installable and usable when the phone has no route to the Pod — walking between
 * houses, for instance. The shell is precached; API calls are never cached, because a stale
 * clinical assessment is worse than no assessment.
 */

const CACHE = 'kangaroo-shell-v1';

const SHELL = [
  '/',
  '/index.html',
  '/style.css',
  '/app.js',
  '/icon.svg',
  '/manifest.webmanifest',
];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim()));
});

self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);

  // Never cache the API. An assessment is about a baby as they are now.
  if (url.pathname.startsWith('/api/')) return;
  if (event.request.method !== 'GET') return;

  event.respondWith(
    caches.match(event.request).then(hit => hit || fetch(event.request).then(response => {
      if (response.ok && url.origin === self.location.origin) {
        const copy = response.clone();
        caches.open(CACHE).then(c => c.put(event.request, copy));
      }
      return response;
    }).catch(() => caches.match('/index.html'))));
});
