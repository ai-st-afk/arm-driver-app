export function openDriverDb(scope, driverId) {
  const name = `arm-driver-web-${scope.replace(/[^a-z0-9]/gi, '_')}-${driverId}`;
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      for (const store of ['assignments', 'events', 'photos']) {
        if (!db.objectStoreNames.contains(store)) db.createObjectStore(store, { keyPath: 'id' });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export function all(db, store) {
  return new Promise((resolve, reject) => {
    const request = db.transaction(store, 'readonly').objectStore(store).getAll();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
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
