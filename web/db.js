export function openDriverDb(scope, driverId) {
  const name = `arm-driver-web-${scope.replace(/[^a-z0-9]/gi, '_')}-${driverId}`;
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1);
    let settled = false;
    const timer = setTimeout(() => {
      settled = true;
      reject(new Error('Локальное хранилище iPhone не ответило за 15 секунд'));
    }, 15000);
    const fail = error => { if (settled) return; settled = true; clearTimeout(timer); reject(error); };
    request.onupgradeneeded = () => {
      const db = request.result;
      for (const store of ['assignments', 'events', 'photos']) {
        if (!db.objectStoreNames.contains(store)) db.createObjectStore(store, { keyPath: 'id' });
      }
    };
    request.onsuccess = () => {
      if (settled) { request.result.close(); return; }
      settled = true; clearTimeout(timer); resolve(request.result);
    };
    request.onerror = () => fail(request.error || new Error('Не удалось открыть локальное хранилище'));
    request.onblocked = () => fail(new Error('Локальное хранилище занято другой вкладкой. Закройте её и повторите.'));
  });
}

export function all(db, store) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(store, 'readonly');
    const request = transaction.objectStore(store).openCursor();
    const records = [];
    let settled = false;
    const timer = setTimeout(() => { settled = true; reject(new Error(`Чтение ${store} не завершилось за 15 секунд`)); }, 15000);
    const fail = error => { if (settled) return; settled = true; clearTimeout(timer); reject(error); };
    request.onsuccess = () => {
      if (settled) return;
      const cursor = request.result;
      if (!cursor) return;
      try {
        records.push(cursor.value);
        cursor.continue();
      } catch (error) { fail(error); }
    };
    request.onerror = () => fail(request.error || new Error(`Не удалось прочитать ${store}`));
    transaction.oncomplete = () => { if (settled) return; settled = true; clearTimeout(timer); resolve(records); };
    transaction.onerror = () => fail(transaction.error || new Error(`Не удалось прочитать ${store}`));
    transaction.onabort = () => fail(transaction.error || new Error(`Чтение ${store} прервано`));
  });
}

export function put(db, store, value) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(store, 'readwrite');
    transaction.objectStore(store).put(value);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

export function clearAssignments(db) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('assignments', 'readwrite');
    transaction.objectStore('assignments').clear();
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

export function putAction(db, event, photos) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(['events', 'photos'], 'readwrite');
    transaction.objectStore('events').put(event);
    for (const photo of photos) transaction.objectStore('photos').put(photo);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

export function cancelAction(db, event, photos) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(['events', 'photos'], 'readwrite');
    transaction.objectStore('events').put({...event, cancelled:true});
    for (const photo of photos) transaction.objectStore('photos').delete(photo.id);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

export function remove(db, store, id) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(store, 'readwrite');
    transaction.objectStore(store).delete(id);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
}
