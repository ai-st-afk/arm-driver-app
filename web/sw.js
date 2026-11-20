const CACHE = 'arm-driver-web-v13';
const SHELL = ['./', './index.html', './app.js', './db.js', './driver-qr.mjs', './vendor/jsQR.js', './style.css', './icon.svg', './brand.svg', './icons.svg', './apple-touch-icon.png', './icon-192.png', './icon-512.png', './manifest.webmanifest'];
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin || !url.pathname.startsWith(new URL('./', self.location.href).pathname)) return;
  event.respondWith(fetch(event.request).catch(() => caches.match(event.request)));
});
self.addEventListener('push', event => {
  let payload = {};
  try { payload = event.data?.json() || {}; } catch { payload = { body: event.data?.text() || '' }; }
  event.waitUntil(Promise.all([
    self.registration.showNotification(payload.title || 'АРМ водителя', {
      body: payload.body || 'Разнарядка обновлена. Откройте приложение.',
      icon: './icon-192.png', badge: './icon-192.png',
      tag: payload.assignment_id || 'assignment',
      data: { url: './' }
    }),
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients =>
      clients.forEach(client => client.postMessage({ type: 'assignment-updated', assignment_id: payload.assignment_id }))
    )
  ]));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
    const app = clients.find(client => client.url.startsWith(new URL('./', self.location.href).href));
    return app ? app.focus() : self.clients.openWindow('./');
  }));
});
