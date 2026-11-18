import { openDriverDb, all, put, putAction, cancelAction, clearAssignments, remove } from './db.js';
import { parseDriverQR, validDriverGUID } from './driver-qr.mjs';

const prefix = location.pathname.split('/web')[0];
const apiRoot = `${prefix}/api/web`;
const stages = ['ПрибылНаПогрузку', 'ЗагрузилсяВПуть', 'ПрибылНаРазгрузку', 'Разгрузился'];
const stageLabels = ['Прибыл на погрузку', 'Загрузился, в пути', 'Прибыл на разгрузку', 'Разгрузился'];
const stageFields = ['arrived_load', 'loaded', 'arrived_unload', 'unloaded'];
const eventLabels = {Ознакомление:'Ознакомление',НачалоСмены:'Начало смены',ПрибылНаПогрузку:'Прибыл на погрузку',ЗагрузилсяВПуть:'Загрузился, в пути',ПрибылНаРазгрузку:'Прибыл на разгрузку',Разгрузился:'Разгрузился',Срыв:'Рейс сорван',ОкончаниеСмены:'Сдача смены'};
const main = document.querySelector('#main');
const nav = document.querySelector('#nav');
const modal = document.querySelector('#modal');
const toast = document.querySelector('#toast');
const connection = document.querySelector('#connection');
const environment = document.querySelector('#environment');
const currentBar = document.querySelector('#current-bar');
const assignmentNotice = document.querySelector('#assignment-notice');
const queueBanner = document.querySelector('#queue-banner');
environment.textContent = 'ТЕСТОВАЯ БАЗА';
environment.hidden = prefix === '/prod';

const state = { driverId: null, dispatcherPhone: '', db: null, assignments: [], events: [], photos: [], screen: 'assignments', serviceOpen: false, selectedId: null, assignmentDetail: false, expandedTripId: null, lastActiveTripId: null, newAssignmentNoticeId: null, dismissedNewAssignmentId: null, dismissedReackVersion: null, refreshing: false, syncing: false, flushAgain: false, busy: false, lastError: '' };
let pairScanner = null;
let heldFlushTimer = null;

const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const label = value => escapeHTML(value || '—');
const currentDate = () => {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {timeZone:'Europe/Kirov',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date()).map(part => [part.type,part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
};
const shortDate = value => {
  if (!value) return '—';
  const today = currentDate();
  const difference = Math.round((Date.parse(`${value}T12:00:00Z`) - Date.parse(`${today}T12:00:00Z`)) / 86400000);
  if (difference === 0) return 'Сегодня';
  if (difference === 1) return 'Завтра';
  if (difference === -1) return 'Вчера';
  return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', timeZone: 'Europe/Kirov' }).format(new Date(`${value}T12:00:00+03:00`));
};
const shortTime = value => { if (!value) return ''; const d = new Date(value); return Number.isNaN(d.getTime()) ? value : new Intl.DateTimeFormat('ru-RU', { hour:'2-digit', minute:'2-digit', day:'2-digit', month:'2-digit', timeZone:'Europe/Kirov' }).format(d); };
const planClock = value => { if (!value) return ''; const d = new Date(value); return Number.isNaN(d.getTime()) ? value : new Intl.DateTimeFormat('ru-RU', { hour:'2-digit', minute:'2-digit', timeZone:'Europe/Kirov' }).format(d); };
function timestamp() {
  const date = new Date();
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const pad = n => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}${sign}${pad(Math.floor(Math.abs(offset)/60))}:${pad(Math.abs(offset)%60)}`;
}
function flash(message) { toast.textContent = message; toast.hidden = false; clearTimeout(flash.timer); flash.timer = setTimeout(() => toast.hidden = true, 4500); }
function errorText(error) { return error?.message || 'Неизвестная ошибка'; }
async function request(path, options = {}) {
  const response = await fetch(apiRoot + path, { credentials: 'same-origin', cache: 'no-store', ...options,
    headers: { ...(options.body instanceof FormData ? {} : options.body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
      ...(options.method && options.method !== 'GET' ? { 'X-Web-Request': '1' } : {}), ...options.headers } });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.error || `Сервер ответил ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return body;
}
function pendingItems() { return [...state.events.filter(e => !e.sent && !e.cancelled), ...state.photos.filter(p => !p.sent)]; }
function scheduleHeldFlush() {
  clearTimeout(heldFlushTimer);
  const next = state.events.filter(e => !e.sent && !e.cancelled && !e.error && e.send_after > Date.now())
    .reduce((earliest, event) => Math.min(earliest, event.send_after), Infinity);
  if (Number.isFinite(next)) heldFlushTimer = setTimeout(() => flush(), Math.max(0, next - Date.now()) + 10);
}
function pendingBanner() {
  const items = pendingItems();
  if (!items.length) return '';
  const error = items.find(item => item.error)?.error || state.lastError;
  return `<div class="pending"><strong>Не отправлено: ${items.length}</strong>${error ? `<div>${escapeHTML(error)}</div>` : 'Проверьте связь с сервером.'}<button class="button secondary" data-action="retry">Повторить</button></div>`;
}
function routeTitle(point) { return label(point?.name || point?.address || 'Адрес не указан'); }
function routeAddress(point) { return point?.address && point.address !== point.name ? `<small class="muted-text">${escapeHTML(point.address)}</small>` : ''; }
function assignmentEvents(id) { return state.events.filter(e => e.assignment_id === id && !e.cancelled && !e.error); }
function undoableEvent(assignmentID, tripID) {
  if (state.syncing) return null;
  const latest = assignmentEvents(assignmentID).filter(e => e.trip_id === tripID && stages.includes(e.type))
    .sort((a,b) => b.time.localeCompare(a.time))[0];
  return latest && !latest.sent && state.photos.filter(p => p.event_id === latest.id).every(p => !p.sent) ? latest : null;
}
function acknowledged(a) {
  const required = a.ack_required_version || a.version;
  return (a.inherited_ack_version || 0) >= required || assignmentEvents(a.id).some(e => e.type === 'Ознакомление' && (e.assignment_version || 0) >= required);
}
function shiftStarted(a) { return !!a.shift_started || assignmentEvents(a.id).some(e => e.type === 'НачалоСмены'); }
function phase(a) {
  if (a.status === 'Заменена') return 'Заменена';
  if (a.shift_closed) return 'Смена закрыта';
  if (assignmentEvents(a.id).some(e => e.type === 'ОкончаниеСмены')) return 'Сдача смены';
  if (a.status === 'Отменена') return 'Отменена диспетчером';
  if (shiftStarted(a)) return 'Смена идёт';
  if (acknowledged(a)) return 'Принята';
  return assignmentEvents(a.id).some(e => e.type === 'Ознакомление') ? 'Изменена' : 'Не принята';
}
function activeAssignment() {
  const list = [...state.assignments];
  const working = list.filter(a => shiftStarted(a) && !a.shift_closed && a.status !== 'Заменена' && !assignmentEvents(a.id).some(e => e.type === 'ОкончаниеСмены'));
  const inherited = working.filter(a => a.basis && a.shift_started && list.some(old => old.id === a.basis.id && old.driver?.id === a.driver?.id));
  const latestInherited = inherited.filter(a => !inherited.some(next => next.basis?.id === a.id));
  if (latestInherited.length > 1) return null;
  const linked = [...new Map(list.filter(old => old.status === 'Заменена').map(old => {
    const next = list.find(a => a.id === old.replacement?.id && a.driver?.id === old.driver?.id && !a.shift_closed && !['Заменена','Отменена'].includes(a.status));
    return next ? [next.id,next] : null;
  }).filter(Boolean)).values()];
  if (linked.length > 1) return null;
  if (latestInherited.length) return latestInherited[0];
  if (working.length) return working.sort((a,b) => (b.shift_started_at || '').localeCompare(a.shift_started_at || '') || b.id.localeCompare(a.id))[0];
  if (linked.length) return linked[0];
  const finishing = list.filter(a => !a.shift_closed && assignmentEvents(a.id).some(e => e.type === 'ОкончаниеСмены'));
  if (finishing.length) return finishing.sort((a,b) => b.departure_day.localeCompare(a.departure_day) || b.id.localeCompare(a.id))[0];
  return list.filter(a => !shiftStarted(a) && !a.shift_closed && !['Заменена','Отменена'].includes(a.status))
    .sort((a,b) => a.departure_day.localeCompare(b.departure_day) || String(a.number || a.id).localeCompare(String(b.number || b.id)) || a.id.localeCompare(b.id))[0] || null;
}
function tripProgress(a, trip) {
  const local = assignmentEvents(a.id).filter(e => e.trip_id === trip.id);
  const previousFailure = local.filter(e => e.type === 'Срыв' && e.sent && e.assignment_version < a.version).sort((x,y) => y.time.localeCompare(x.time))[0];
  const resetAfterFailure = previousFailure && trip.status === 'Назначена';
  const effective = local.filter(e => !resetAfterFailure || !(e.type === 'Срыв' && e.sent && e.assignment_version < a.version) && !(e.type === 'ПрибылНаРазгрузку' && e.time <= previousFailure.time));
  const done = new Set(effective.map(e => e.type));
  stageFields.forEach((field, i) => { if (trip.stages?.[field]) done.add(stages[i]); });
  const inactive = !['Назначена', 'Активна'].includes(trip.status);
  const failureSent = trip.status === 'Сорвана' || effective.some(e => e.type === 'Срыв' && e.sent);
  const failurePending = !failureSent && done.has('Срыв');
  const resolved = inactive || done.has('Разгрузился') || failureSent || failurePending || trip.next_stage === '';
  const index = trip.next_stage == null ? 0 : stages.indexOf(trip.next_stage);
  const next = resolved || index < 0 ? null : stages.slice(index).find(type => !done.has(type)) || null;
  return { done, resolved, next, invalidNext:!resolved && index < 0, failed:failureSent, failurePending, returned: !!resetAfterFailure,
    cargoInVehicle: !!trip.cargo_in_vehicle || done.has('ЗагрузилсяВПуть') && !done.has('Разгрузился') };
}
function cleanAssignment(a) {
  const {ack_required_version,inherited_ack_version,changed_unload_trip_ids,updated_at,...business} = a;
  return business;
}
function stableJSON(value) {
  if (Array.isArray(value)) return `[${value.map(stableJSON).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).filter(key => value[key] !== undefined).sort().map(key => `${JSON.stringify(key)}:${stableJSON(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
}
function keysOnlyVersionBump(old, fresh) {
  if (!old || fresh.version <= old.version) return false;
  return stableJSON({...cleanAssignment(old),version:fresh.version,keys_issued:fresh.keys_issued,keys_issued_at:fresh.keys_issued_at}) === stableJSON(fresh);
}
function routeChanged(old, fresh) {
  if (!old) return false;
  const prior = cleanAssignment(old);
  const priorTrips = prior.trips.map(trip => {
    const incoming = fresh.trips.find(item => item.id === trip.id);
    const updated = {...trip,basis_trip_id:incoming?.basis_trip_id,stages:incoming?.stages,cargo_in_vehicle:incoming?.cargo_in_vehicle,next_stage:incoming?.next_stage};
    if (incoming?.status === 'Сорвана' || trip.status === 'Сорвана' && incoming?.status === 'Назначена' && incoming.stages?.unloaded)
      return {...updated,status:incoming.status,cancel_reason:incoming.cancel_reason};
    return updated;
  });
  const comparable = {...prior,version:fresh.version,keys_issued:fresh.keys_issued,keys_issued_at:fresh.keys_issued_at,
    keys_accepted:fresh.keys_accepted,keys_accepted_at:fresh.keys_accepted_at,shift_closed:fresh.shift_closed,
    shift_closed_at:fresh.shift_closed_at,shift_started:fresh.shift_started,shift_started_at:fresh.shift_started_at,
    replacement:fresh.replacement,basis:fresh.basis,trips:priorTrips};
  return stableJSON(comparable) !== stableJSON(fresh);
}
function mergeAssignment(previous, fresh) {
  if (previous && !Array.isArray(previous.trips)) previous = {...previous,trips:[]};
  if (!Array.isArray(fresh.trips)) fresh = {...fresh,trips:[]};
  if (previous && fresh.version <= previous.version) return previous;
  const cancelledNow = fresh.trips.some(trip => trip.status === 'Отменена' && previous?.trips.find(old => old.id === trip.id)?.status !== 'Отменена');
  const ackRequired = !previous || cancelledNow || routeChanged(previous,fresh) ? fresh.version : previous.ack_required_version || previous.version;
  const inherited = previous?.inherited_ack_version || (fresh.basis && fresh.shift_started ? fresh.version : 0);
  const present = new Set(fresh.trips.map(trip => trip.id));
  const vanished = previous?.trips.filter(trip => !present.has(trip.id)).map(trip => ({...trip,status:'Отменена'})) || [];
  const changed = fresh.trips.filter(trip => {
    const old = previous?.trips.find(item => item.id === trip.id);
    return old && stableJSON(old.unload_point) !== stableJSON(trip.unload_point);
  }).map(trip => trip.id);
  const changedUnload = [...new Set([...changed,...(previous?.changed_unload_trip_ids || [])])].filter(id => present.has(id));
  return { ...fresh, trips:[...fresh.trips,...vanished], updated_at:Date.now(), ack_required_version:ackRequired,
    inherited_ack_version:inherited, changed_unload_trip_ids:changedUnload };
}
function currentSelection() {
  const selected = selectedAssignment(state.selectedId);
  if (selected?.status === 'Заменена') return selectedAssignment(selected.replacement?.id) || activeAssignment() || selected;
  return selected || activeAssignment();
}

async function reloadLocal() {
  [state.assignments, state.events, state.photos] = await Promise.all(['assignments','events','photos'].map(name => all(state.db, name)));
  state.assignments = state.assignments.map(a => ({...a,trips:Array.isArray(a.trips) ? a.trips : []}));
  state.assignments.sort((a,b) => b.departure_day.localeCompare(a.departure_day) || b.version - a.version);
}
async function refreshAssignments() {
  if (state.refreshing) return;
  state.refreshing = true;
  try {
    const response = await request('/assignments');
    const known = new Set(state.assignments.map(a => a.id));
    const autoAck = [];
    for (const raw of response.assignments || []) {
      const item = {...raw,trips:Array.isArray(raw.trips) ? raw.trips : []};
      const previous = state.assignments.find(a => a.id === item.id);
      if (previous && phase(previous) === 'Принята' && keysOnlyVersionBump(previous,item)) autoAck.push(item.id);
      const merged = mergeAssignment(previous, item);
      if (merged !== previous) await put(state.db, 'assignments', merged);
    }
    const visibleIds = new Set((response.assignments || []).map(item => item.id));
    for (const prior of state.assignments) {
      if (visibleIds.has(prior.id)) continue;
      const status = phase(prior);
      if (shiftStarted(prior) && !prior.shift_closed || !prior.shift_closed && ['Сдача смены','Заменена'].includes(status)) continue;
      await remove(state.db, 'assignments', prior.id);
    }
    state.lastError = '';
    await reloadLocal();
    for (const id of autoAck) {
      const assignment = selectedAssignment(id);
      if (!assignment || assignmentEvents(id).some(event => event.type === 'Ознакомление' && event.assignment_version === assignment.version)) continue;
      const moment = await eventMoment();
      await put(state.db, 'events', {id:moment.id,type:'Ознакомление',driver_id:state.driverId,assignment_id:id,
        trip_id:null,time:moment.time,assignment_version:assignment.version,sent:false,comment:'',geo:moment.geo});
    }
    if (autoAck.length) { await reloadLocal(); await flush(); }
    const added = (response.assignments || []).find(item => !known.has(item.id) && item.status !== 'Заменена' && item.status !== 'Отменена');
    if (added) state.newAssignmentNoticeId = added.id;
    state.selectedId = currentSelection()?.id || state.assignments[0]?.id || null;
    render();
  } catch (error) {
    if (error.status === 401) { expireSession(); return; }
    state.lastError = `Разнарядки не обновились: ${errorText(error)}`;
    render();
  } finally {
    state.refreshing = false;
  }
}
async function initDriver(driverId) {
  state.driverId = driverId;
  try { localStorage.setItem(`arm_driver_last_${prefix || 'test3'}`, driverId); }
  catch (error) { console.warn('Не удалось сохранить привязку локально', error); }
  state.db?.close();
  state.db = await openDriverDb(prefix || 'test3', driverId);
  await reloadLocal();
  scheduleHeldFlush();
  state.selectedId = activeAssignment()?.id || state.assignments[0]?.id || null;
  nav.hidden = false;
  if (navigator.storage?.persist) navigator.storage.persist().catch(() => {});
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('./sw.js').catch(error => console.warn('Service worker не зарегистрирован', error));
  render();
  await refreshAssignments();
  await flush();
}
async function start() {
  window.addEventListener('online', () => { connection.textContent = ''; if (state.driverId) { refreshAssignments(); flush(); } });
  window.addEventListener('offline', () => { connection.textContent = 'Нет сети. Неотправленные действия остаются на этом iPhone.'; });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && pairScanner) closeModal();
    if (!document.hidden && state.driverId) { refreshAssignments(); flush(); }
  });
  navigator.serviceWorker?.addEventListener('message', event => {
    if (event.data?.type === 'assignment-updated' && state.driverId) refreshAssignments();
  });
  connection.textContent = navigator.onLine ? '' : 'Нет сети. Неотправленные действия остаются на этом iPhone.';
  let session;
  const sessionController = new AbortController();
  const sessionTimer = setTimeout(() => sessionController.abort(), 15000);
  try { session = await request('/session', {signal:sessionController.signal}); }
  catch (error) {
    let last = null;
    try { last = localStorage.getItem(`arm_driver_last_${prefix || 'test3'}`); } catch { /* Браузер мог запретить localStorage. */ }
    if (error.status === 401 || !last) { renderUnconfigured(); return; }
    try {
      await initDriver(last);
      state.lastError = 'Сервер недоступен: данные взяты из памяти iPhone';
      render();
    } catch (storageError) { renderStartupError(storageError); }
    return;
  } finally { clearTimeout(sessionTimer); }
  state.dispatcherPhone = session.dispatcher_phone || '';
  try { await initDriver(session.driver_id); }
  catch (error) { renderStartupError(error); }
}

function renderStartupError(error) {
  console.error('Не удалось открыть локальные данные водителя', error);
  nav.hidden = true;
  currentBar.hidden = true;
  assignmentNotice.hidden = true;
  queueBanner.innerHTML = '';
  main.innerHTML = `<section class="page"><h1 class="section-title">Не удалось открыть данные телефона</h1>
    <p class="inline-error">${escapeHTML(errorText(error))}</p>
    <p>Сохранённые события и фото не удалены. Попробуйте открыть приложение ещё раз.</p>
    <button class="button wide" data-action="reload-app">Повторить</button></section>`;
}

function renderUnconfigured() {
  state.serviceOpen = false;
  nav.hidden = true;
  currentBar.hidden = true;
  assignmentNotice.hidden = true;
  queueBanner.innerHTML = '';
  main.innerHTML = '<section class="page unconfigured"><h1 class="section-title">Телефон не настроен</h1><p class="subtitle">Обратитесь к диспетчеру, чтобы получить настроенный телефон.</p><button class="service-link" data-action="service-open">Служебный вход</button></section>';
}

function renderService() {
  main.innerHTML = `<section class="page pair-wrap"><h1 class="screen-title">Служебный вход</h1><p class="subtitle">Привязка телефона к водителю. Идентификатор водителя берётся из 1С, водителю его вводить не нужно.</p>
    ${state.driverId ? '<p class="subtitle">Телефон уже привязан. Чтобы привязать к другому водителю, отсканируйте его QR-код или введите GUID.</p>' : ''}
    <button class="button wide large" id="scan-driver-qr" type="button">Сканировать QR-код</button>
    <p class="subtitle service-manual-label">Или введите вручную:</p><form id="driver-form"><label class="field"><span>Идентификатор водителя из 1С</span><input name="driver_id" autocomplete="off" autocapitalize="off" spellcheck="false" required></label><button class="button secondary wide large">Привязать по введённому</button></form>
    <p class="subtitle">Очистка убирает загруженные разнарядки только с этого телефона. События, фото и привязка водителя сохраняются.</p>
    <button class="button secondary wide" data-action="clear-assignments" ${state.db ? '' : 'disabled'}>Очистить разнарядки на телефоне</button>
    <button class="service-link" data-action="service-close">Закрыть</button></section>`;
  document.querySelector('#scan-driver-qr').onclick = startPairScan;
  document.querySelector('#driver-form').onsubmit = async event => {
    event.preventDefault();
    const button = event.target.querySelector('button'); button.disabled = true;
    const driverID = event.target.elements.driver_id.value.trim();
    if (!validDriverGUID(driverID)) { flash('Нужен GUID водителя из 1С'); button.disabled = false; return; }
    try { await bindDriver(driverID); }
    catch (error) { flash(errorText(error)); button.disabled = false; }
  };
}

async function bindDriver(driverID, name = null) {
  if (state.driverId && state.driverId !== driverID && pendingItems().length) {
    throw new Error('Сначала отправьте очередь событий и фото прежнего водителя.');
  }
  let subscription = null;
  if (state.driverId && state.driverId !== driverID) {
    subscription = await navigator.serviceWorker?.ready.then(reg => reg.pushManager?.getSubscription());
    if (subscription) await request('/push/subscriptions', {method:'DELETE', body:JSON.stringify({endpoint:subscription.endpoint})});
  }
  const session = await request('/pair-driver', {method:'POST', body:JSON.stringify({driver_id:driverID})});
  state.dispatcherPhone = session.dispatcher_phone || '';
  if (name) localStorage.setItem(`arm_driver_name_${prefix || 'test3'}_${session.driver_id}`, name);
  state.serviceOpen = false;
  state.screen = 'settings';
  await initDriver(session.driver_id);
  if (subscription) {
    try { await request('/push/subscriptions', {method:'POST', body:JSON.stringify(subscription.toJSON())}); }
    catch (error) { flash(`Телефон привязан. Включите уведомления заново: ${errorText(error)}`); return; }
  }
  flash('Телефон привязан');
}

async function startPairScan() {
  if (!navigator.mediaDevices?.getUserMedia) { flash('Камера недоступна. Введите GUID вручную.'); return; }
  if (typeof window.jsQR !== 'function') { flash('Сканер не загрузился. Обновите страницу.'); return; }
  const scan = {active:true, stream:null, frame:0, lastFrame:0};
  pairScanner = scan;
  showModal('<h2>QR-код водителя</h2><p class="subtitle">Наведите камеру на QR из карточки водителя в 1С.</p><video class="camera-video" autoplay playsinline muted></video><button class="button secondary wide" id="cancel-pair-scan">Отмена</button>');
  modal.querySelector('#cancel-pair-scan').onclick = closeModal;
  const video = modal.querySelector('video');
  try {
    const stream = await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'}},audio:false});
    if (!scan.active) { stream.getTracks().forEach(track => track.stop()); return; }
    scan.stream = stream;
    video.srcObject = stream;
    await video.play();
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d', {willReadFrequently:true});
    if (!context) throw new Error('Не удалось открыть сканер');
    const frame = time => {
      if (!scan.active) return;
      if (time - scan.lastFrame >= 180 && video.readyState >= 2 && video.videoWidth && video.videoHeight) {
        scan.lastFrame = time;
        const scale = Math.min(1, 640 / video.videoWidth);
        canvas.width = Math.round(video.videoWidth * scale);
        canvas.height = Math.round(video.videoHeight * scale);
        let result = null;
        try {
          context.drawImage(video, 0, 0, canvas.width, canvas.height);
          const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
          result = window.jsQR(pixels.data, pixels.width, pixels.height, {inversionAttempts:'dontInvert'});
        } catch (error) { console.warn('Кадр QR не распознан', error); }
        if (result) {
          const driver = parseDriverQR(result.data);
          closeModal();
          if (!driver) { flash('Нужен QR-код из карточки водителя в 1С'); return; }
          bindDriver(driver.driverId, driver.name).catch(error => flash(errorText(error)));
          return;
        }
      }
      scan.frame = requestAnimationFrame(frame);
    };
    scan.frame = requestAnimationFrame(frame);
  } catch (error) {
    closeModal();
    flash(error?.name === 'NotAllowedError' ? 'Разрешите доступ к камере или введите GUID вручную.' : errorText(error));
  }
}
function expireSession() {
  state.db?.close();
  state.db = null;
  state.driverId = null;
  renderUnconfigured();
  flash('Привязка недействительна. Обратитесь к диспетчеру; сохранённая очередь останется на этом iPhone.');
}
function renderNav() {
  const entries = [['assignments','assignment','Разнарядка'],['trips','truck','Мои рейсы'],['history','history','История'],['settings','settings','Настройки']];
  nav.innerHTML = entries.map(([key,icon,title]) => `<button type="button" data-screen="${key}" class="${state.screen === key ? 'selected' : ''}"><svg aria-hidden="true"><use href="icons.svg#${icon}"></use></svg><span>${title}</span></button>`).join('');
}
function render() {
  if (!state.driverId) return state.serviceOpen ? renderService() : renderUnconfigured();
  connection.textContent = !navigator.onLine ? 'Нет сети. Неотправленные действия остаются на этом iPhone.' :
    /^(Разнарядки не обновились|Сервер недоступен)/.test(state.lastError) ? state.lastError : '';
  nav.hidden = false;
  queueBanner.innerHTML = pendingBanner();
  const current = activeAssignment();
  currentBar.hidden = !current;
  if (current) {
    currentBar.textContent = `Разнарядка № ${current.number || current.id} — ${phase(current).toLowerCase()}`;
    currentBar.classList.toggle('in-shift', phase(current) === 'Смена идёт');
  }
  const added = selectedAssignment(state.newAssignmentNoticeId);
  assignmentNotice.hidden = !added;
  if (added) assignmentNotice.innerHTML = `<button data-action="open-new-assignment" data-id="${escapeHTML(added.id)}">${added.basis ? `ЗАМЕНА МАШИНЫ — РАЗНАРЯДКА № ${label(added.number || added.id)}. ПРОВЕРИТЬ МАШИНУ И РЕЙСЫ` : `НОВАЯ РАЗНАРЯДКА № ${label(added.number || added.id)} НА СЕГОДНЯ — ОТКРЫТЬ И ОЗНАКОМИТЬСЯ`}</button>`;
  renderNav();
  if (state.screen === 'assignments') renderAssignments();
  if (state.screen === 'trips') renderTrips();
  if (state.screen === 'history') renderHistory();
  if (state.screen === 'settings') state.serviceOpen ? renderService() : renderSettings();
  if (added && added.id !== state.dismissedNewAssignmentId && modal.hidden && !state.busy) {
    showModal(`<h2>Пришла ещё одна разнарядка</h2><p>${added.basis ? added.shift_started ? `Машину заменили. Откройте разнарядку № ${label(added.number || added.id)} и проверьте новые рейсы. Смена продолжается; для рейсов нужны ключи от новой машины.` : `Машину заменили, но 1С ещё не передала начало смены в новую разнарядку № ${label(added.number || added.id)}. Откройте маршрут; этапы пока недоступны.` : `Разнарядка № ${label(added.number || added.id)} на сегодня. Откройте её, проверьте машину и рейсы, затем нажмите «Ознакомлен».`}</p><button class="button wide large" data-action="open-new-assignment" data-id="${escapeHTML(added.id)}">Открыть разнарядку</button><button class="button secondary wide" data-action="dismiss-new-assignment">Позже</button>`);
  } else if (state.screen === 'trips' && current && shiftStarted(current) && !acknowledged(current) && state.dismissedReackVersion !== `${current.id}:${current.version}` && modal.hidden && !state.busy) {
    showModal(`<h2>Маршрут изменён</h2><p>Проверьте новый маршрут и нажмите «Ознакомлен» на вкладке «Разнарядка». Уже отмеченные этапы сохранятся.</p><button class="button wide large" data-action="open-reack" data-id="${escapeHTML(current.id)}">Открыть разнарядку</button><button class="button secondary wide" data-action="dismiss-reack" data-id="${escapeHTML(current.id)}">Позже</button>`);
  }
}
function field(name, value) { return `<div class="labeled-field"><span>${escapeHTML(name)}</span><strong>${label(value)}</strong></div>`; }
function dispatcherButton(text = 'Позвонить диспетчеру') {
  const phone = state.dispatcherPhone.replace(/[^+\d]/g, '');
  return phone ? `<a class="button secondary wide call-button" href="tel:${escapeHTML(phone)}">☎ ${escapeHTML(text)}</a>` : '';
}
function pointField(name, point) {
  const address = point?.address || point?.name;
  if (!address) return field(name, '—');
  const href = `https://maps.apple.com/?q=${encodeURIComponent(address)}`;
  return `<a class="labeled-field nav-point" href="${escapeHTML(href)}" target="_blank" rel="noopener"><span>${escapeHTML(name)}</span><strong>${escapeHTML(address)}</strong></a>`;
}
function cargoDetails(cargo) {
  if (!cargo) return '';
  const items = cargo.positions?.items;
  let content = '';
  if (!cargo.positions) {
    if (cargo.composition) content += field('Описание груза', cargo.composition);
    content += '<p class="muted-text">Количество по позициям ещё не передано</p>';
  } else if (!items?.length) {
    content += '<p>Нет позиций груза</p>';
  } else {
    content += `<details class="details"><summary>Товары (${items.length})</summary>${items.map(item => `${field(item.name, `${item.quantity} ${item.unit}`)}<p class="muted-text">Вес: ${label(item.weight)} т · Объём: ${label(item.volume)} м³</p>`).join('')}</details>`;
  }
  if (cargo.weight && Number(cargo.weight) !== 0) content += field('Общий вес', `${cargo.weight} т`);
  if (cargo.volume && Number(cargo.volume) !== 0) content += field('Общий объём', `${cargo.volume} м³`);
  return `<div class="cargo-details">${content}</div>`;
}
function assignmentCard(a, isCurrent) {
  const status = phase(a);
  const pending = ['Не принята','Изменена','Принята'].includes(status);
  const summary = a.trips.length ? `${a.trips.length} ездок · ${routeTitle(a.trips[0].load_point)} → ${routeTitle(a.trips[0].unload_point)}` : 'Ездок пока нет';
  return `<article class="card ${pending ? 'pending-card' : ''} ${isCurrent ? 'current' : ''}"><button class="card-button" data-action="open-assignment" data-id="${escapeHTML(a.id)}"><h2>Разнарядка № ${label(a.number || a.id)}</h2><div class="card-status">${isCurrent ? 'текущая · ' : ''}${escapeHTML(status.toLowerCase())}</div><div class="card-summary">${summary}</div></button></article>`;
}
function renderAssignments() {
  const currentId = activeAssignment()?.id;
  const list = [...state.assignments].sort((a,b) => (a.id === currentId ? -1 : 0) - (b.id === currentId ? -1 : 0) || a.departure_day.localeCompare(b.departure_day) || String(a.number || a.id).localeCompare(String(b.number || b.id)));
  if (list.length === 1 || state.assignmentDetail && selectedAssignment(state.selectedId)) {
    main.innerHTML = renderSelectedAssignment();
    return;
  }
  if (!list.length) {
    main.innerHTML = state.lastError.startsWith('Разнарядки не обновились') ? `<section class="page assignment-list"><div class="empty">Не удалось загрузить разнарядки<br>${escapeHTML(state.lastError)}</div><button class="button secondary wide" data-action="refresh">Повторить</button></section>` : '<section class="page assignment-list"><div class="empty">Разнарядок пока нет</div></section>';
    return;
  }
  let lastDay = '';
  const cards = list.map(a => {
    const header = a.departure_day !== lastDay ? `<h2 class="list-date">${shortDate(a.departure_day)}</h2>` : '';
    lastDay = a.departure_day;
    return header + assignmentCard(a, a.id === activeAssignment()?.id);
  }).join('');
  main.innerHTML = `<section class="page assignment-list">${cards}</section>`;
}
function renderSelectedAssignment() {
  const a = selectedAssignment(state.selectedId) || activeAssignment();
  if (!a) return `<section class="page"><div class="empty">Нет разнарядки.</div></section>`;
  state.selectedId = a.id;
  const status = phase(a), ack = acknowledged(a), shift = shiftStarted(a);
  const canAck = a.status !== 'Заменена' && a.status !== 'Отменена' && !a.shift_closed && !ack;
  const continuesSameDriver = !!a.basis && state.assignments.some(previous => previous.id === a.basis.id && previous.driver?.id === a.driver?.id);
  const otherOpen = state.assignments.find(other => other.id !== a.id && shiftStarted(other) && !other.shift_closed && other.status !== 'Заменена' && other.status !== 'Отменена');
  const canStart = !shift && ack && a.keys_issued && a.departure_day === currentDate() && a.status !== 'Отменена' && !continuesSameDriver && !otherOpen;
  const future = a.departure_day > currentDate();
  const keyMessage = !a.keys_issued ? 'Ожидайте выдачи ключей механиком.' : a.departure_day > currentDate() ? `Начать смену можно только в день выезда — ${a.departure_day}.` : a.departure_day < currentDate() ? `Дата выезда ${a.departure_day} прошла. Уточните разнарядку у диспетчера.` : '';
  const resolvedCount = a.trips.filter(t => tripProgress(a,t).resolved).length;
  const progressText = a.trips.length === 0 ? 'Рейсов нет' : resolvedCount >= a.trips.length ? 'Все рейсы закрыты' : `Рейсы: ${resolvedCount} из ${a.trips.length} закрыто`;
  const trailerFields = shift ? a.trips.filter(t => t.trailer?.plate).map(t => field(`Прицеп · рейс ${t.order}${t.status === 'Отменена' ? ' (снят)' : ''}`,t.trailer.plate)).join('') : '';
  const planTrips = !shift && !['Заменена','Отменена'].includes(a.status) ? `<h2 class="section-label">Ездки по плану</h2><p class="subtitle">Это только просмотр. Отмечать этапы нужно после начала смены во вкладке «Мои рейсы».</p>${[...a.trips].sort((x,y) => x.order-y.order).map(t => `<div class="upcoming-trip"><strong>${t.order}. ${routeTitle(t.load_point)} → ${routeTitle(t.unload_point)}</strong>${t.status === 'Отменена' ? `<div class="inline-error">Рейс отменён${t.cancel_reason ? ': ' + escapeHTML(t.cancel_reason) : ''}</div>` : ''}${field('Погрузка', t.load_point?.address || t.load_point?.name)}${field('Разгрузка', t.unload_point?.address || t.unload_point?.name)}${t.trailer?.plate ? field('Прицеп',t.trailer.plate) : ''}</div>`).join('') || '<p>Ездки ещё не переданы</p>'}` : '';
  const total = a.shift_total ? cargoDetails(a.shift_total) : '<p>Итог за смену ещё не передан</p>';
  return `<section class="page"><div class="toolbar">${state.assignments.length > 1 ? '<button class="toolbar-back" data-action="back-assignments" aria-label="К списку">‹</button>' : '<span></span>'}<span class="status-pill ${status === 'Смена идёт' ? 'active' : ''}">${escapeHTML(status)}</span></div>
    <div class="app-header"><img src="brand.svg" alt="">АРМ водителя</div><h1 class="section-title">Разнарядка № ${label(a.number || a.id)}</h1>
    <div class="fields">${field('Дата выезда',a.departure_day)}${field('Водитель',a.driver?.name || a.driver?.id)}${field('Машина',[a.vehicle?.name,a.vehicle?.plate].filter(Boolean).join(' '))}${field('Рейсов на смену',a.trips.length)}${trailerFields}</div>
    ${future ? `<div class="notice amber"><strong>${shortDate(a.departure_day) === 'Завтра' ? 'Разнарядка на завтра.' : 'Будущая разнарядка.'}</strong><br>Сначала посмотрите все ездки ниже, затем нажмите «Ознакомился и принял».</div>` : ''}
    ${planTrips}<h2 class="section-label">Итого за смену</h2>${total}
    ${a.updated_at ? `<p class="muted-text">Обновлено в ${escapeHTML(planClock(new Date(a.updated_at).toISOString()))}</p>` : ''}
    ${a.cancel_reason ? `<div class="inline-error">${escapeHTML(a.cancel_reason)}</div>` : ''}
    ${a.status === 'Заменена' ? `<div class="notice red">Разнарядка заменена на № ${label(a.replacement?.number || a.replacement?.id)}. Рейсы этой разнарядки больше не отмечаются.</div>` : ''}
    ${a.status === 'Отменена' ? '<div class="notice red">Разнарядка отменена диспетчером. Дождитесь закрытия смены в 1С после осмотра медика.</div>' : ''}
    ${continuesSameDriver && !shift ? '<div class="notice red">Разнарядка назначена взамен прежней, но 1С ещё не передала «СменаНачата=true». Дождитесь обновления.</div>' : ''}
    ${continuesSameDriver && shift && !a.keys_issued ? '<div class="notice red">Смена продолжается по новой разнарядке. Дождитесь выдачи ключей от новой машины механиком.</div>' : ''}
    <div class="assignment-actions">${canAck ? `<button class="button wide large" data-action="ack" data-id="${escapeHTML(a.id)}">${shift ? 'Ознакомлен' : 'Ознакомился и принял'}</button>` : !shift && a.status !== 'Заменена' && a.status !== 'Отменена' ? '<button class="button wide large" disabled>Разнарядка принята</button>' : ''}
    ${!shift && a.status !== 'Заменена' && a.status !== 'Отменена' ? `<button class="button orange wide large" data-action="start" data-id="${escapeHTML(a.id)}" ${canStart ? '' : 'disabled'}>Начать смену</button>` : ''}
    ${!shift && ack && !canStart && keyMessage ? `<div class="inline-error">${escapeHTML(keyMessage)}</div>` : ''}
    ${!shift && otherOpen ? `<div class="inline-error">Сначала дождитесь закрытия смены по разнарядке № ${label(otherOpen.number || otherOpen.id)} в 1С.</div>` : ''}
    ${shift && !a.shift_closed && a.status !== 'Заменена' && a.status !== 'Отменена' ? `<p><strong>${escapeHTML(progressText)}</strong></p>${a.trips.some(t => t.status === 'Отменена') ? '<p class="muted-text">Часть рейсов сняли диспетчером.</p>' : ''}<p>${resolvedCount >= a.trips.length ? a.keys_accepted ? 'Рейсы завершены. Пройдите осмотр у медика. Смену закроет 1С после его подтверждения.' : 'Рейсы завершены. Сдайте ключи механику и пройдите осмотр у медика. Смену закроет 1С.' : a.keys_accepted ? 'Ключи сданы, но есть незавершённые рейсы. Для каждого укажите «Не могу выполнить рейс» с причиной или дождитесь отмены диспетчером. После этого медик сможет закрыть смену.' : 'После рейсов сдайте ключи механику и пройдите осмотр у медика. Смену закроет 1С.'}</p>` : ''}
    ${a.shift_closed ? '<p>Смена закрыта</p>' : ''}
    ${a.status !== 'Заменена' && !a.shift_closed ? dispatcherButton() : ''}
    </div></section>`;
}
function canWork(a) { return shiftStarted(a) && !a.shift_closed && !['Заменена','Отменена'].includes(a.status) && (!a.shift_started || a.keys_issued); }
function tripCard(a, trip, activeId) {
  const progress = tripProgress(a, trip);
  const complete = progress.done.has('Разгрузился');
  const failed = trip.status === 'Отменена' || trip.status === 'Сорвана' || progress.failed || progress.failurePending;
  const isActive = activeId === trip.id && !progress.resolved;
  const status = trip.status === 'Отменена' ? 'снят' : failed ? 'сорван' : progress.failurePending ? 'ожидает отправки' : complete ? 'выполнен' : isActive ? 'сейчас' : 'ожидает';
  const nextIndex = stages.indexOf(progress.next);
  const canFail = canWork(a) && !progress.resolved;
  const actionable = canWork(a) && (isActive || progress.returned) && progress.next;
  const expanded = state.expandedTripId === trip.id;
  const contacts = (trip.contacts || []).map(c => `<p>${label(c.role)}: ${label(c.name)} ${(c.phones || []).map(p => `<a href="tel:${escapeHTML(String(p.number || '').replace(/[^+\d]/g,''))}">${label(p.display || p.number)}</a>`).join(', ')}</p>`).join('');
  const stageTimes = stageFields.map((key,i) => trip.stages?.[key] ? field(stageLabels[i],shortTime(trip.stages[key])) : '').join('');
  const time = [planClock(trip.plan_load),planClock(trip.plan_unload)].filter(Boolean).join(' — ');
  return `<article class="trip-card ${isActive ? 'active' : ''} ${failed ? 'failed' : ''}"><button class="trip-summary" data-action="toggle-trip" data-trip="${escapeHTML(trip.id)}" aria-expanded="${expanded}"><span class="trip-index ${complete ? 'done' : failed ? 'failed' : ''}">${complete ? '✓' : failed ? '×' : trip.order}</span><span class="trip-head-text"><span class="trip-route">${routeTitle(trip.load_point)} → ${routeTitle(trip.unload_point)}</span><span class="trip-meta"><strong>${status}</strong>${time ? ` · ${escapeHTML(time)}` : ''}</span></span></button>
    ${a.changed_unload_trip_ids?.includes(trip.id) ? `<div class="trip-notice">${trip.return_to_depot ? 'Возврат на завод' : progress.returned ? 'Рейс возвращён: новый адрес' : 'Изменён адрес разгрузки'}: ${routeTitle(trip.unload_point)}</div>` : trip.return_to_depot ? `<div class="trip-notice">Возврат на завод: ${routeTitle(trip.unload_point)}</div>` : ''}
    ${trip.cancel_reason ? `<div class="trip-notice">${escapeHTML(trip.cancel_reason)}</div>` : ''}
    ${progress.returned && !a.changed_unload_trip_ids?.includes(trip.id) ? `<div class="trip-notice">Рейс возвращён: новый адрес ${routeTitle(trip.unload_point)}</div>` : ''}
    ${trip.trailer?.plate ? field('Прицеп',trip.trailer.plate) : ''}
    ${cargoDetails(trip.cargo)}
    ${progress.cargoInVehicle ? '<div class="notice active">Груз в машине</div>' : ''}
    <div class="trip-body" ${expanded ? '' : 'hidden'}>${pointField('Погрузка',trip.load_point)}${pointField('Разгрузка',trip.unload_point)}${trip.customer ? field('Заказчик',trip.customer) : ''}${stageTimes}${contacts}
    ${trip.status === 'Отменена' ? '<div class="inline-error">Рейс отменён</div>' : ''}
    ${complete ? '<div class="notice active">Рейс выполнен</div>' : ''}
    ${failed && trip.status !== 'Отменена' ? '<div class="inline-error">Не выполнена</div>' : ''}
    ${progress.failurePending ? '<div class="inline-error">Отказ записан, ожидает отправки</div>' : ''}
    ${progress.invalidNext ? `<div class="inline-error">Неизвестный следующий этап от 1С: ${label(trip.next_stage)}. Сообщите диспетчеру.</div>` : ''}
    ${canFail ? `<button class="button secondary wide" data-action="trip-fail" data-assignment="${escapeHTML(a.id)}" data-trip="${escapeHTML(trip.id)}">Не могу выполнить рейс</button>` : ''}
    ${actionable ? `<button class="button orange wide large" data-action="trip-step" data-assignment="${escapeHTML(a.id)}" data-trip="${escapeHTML(trip.id)}" data-type="${escapeHTML(progress.next)}">${escapeHTML(stageLabels[nextIndex])}</button>` : ''}
    ${undoableEvent(a.id,trip.id) ? `<button class="button secondary wide" data-action="trip-undo" data-assignment="${escapeHTML(a.id)}" data-trip="${escapeHTML(trip.id)}">Отмена</button>` : ''}
    ${!canWork(a) && !progress.resolved ? `<p class="muted-text">${!shiftStarted(a) ? 'Начните смену, чтобы отмечать этапы этого рейса.' : !a.keys_issued ? 'Дождитесь выдачи ключей от новой машины.' : 'Рейс сейчас недоступен.'}</p>` : ''}
    ${canWork(a) && !isActive && !progress.resolved ? '<p class="muted-text">Рейс станет доступен, когда закроется текущий.</p>' : ''}</div></article>`;
}
function renderTrips() {
  const a = activeAssignment();
  if (!a) { main.innerHTML = state.lastError.startsWith('Разнарядки не обновились') ? `<section class="page roadmap-page"><div class="empty">Не удалось загрузить разнарядки<br>${escapeHTML(state.lastError)}</div><button class="button secondary wide" data-action="refresh">Повторить</button></section>` : '<section class="page roadmap-page"><div class="empty">Нет активной разнарядки.<br>Рейсы появятся здесь, когда диспетчер пришлёт новую.</div></section>'; return; }
  state.selectedId = a.id;
  const trips = [...a.trips].sort((x,y) => x.order - y.order);
  const normalActive = trips.find(t => { const p = tripProgress(a,t); return !p.resolved && !p.returned; })?.id || trips.find(t => !tripProgress(a,t).resolved)?.id;
  if (normalActive !== state.lastActiveTripId) { state.expandedTripId = normalActive || null; state.lastActiveTripId = normalActive || null; }
  const selectedReturned = trips.find(t => t.id === state.expandedTripId && tripProgress(a,t).returned && !tripProgress(a,t).resolved)?.id;
  const activeId = selectedReturned || normalActive;
  const needsAck = !acknowledged(a) && shiftStarted(a);
  main.innerHTML = `<section class="page roadmap-page"><h2 class="screen-title">Разнарядка № ${label(a.number || a.id)}</h2>
    ${needsAck ? '<div class="inline-error">Маршрут изменён. Откройте разнарядку и нажмите «Ознакомлен».</div>' : ''}
    ${a.status === 'Заменена' ? '<div class="inline-error">Разнарядка заменена. Выберите новую в списке.</div>' : ''}
    ${!shiftStarted(a) ? '<div class="inline-error">Смена не начата. Отметить рейс можно после «Начать смену» на вкладке «Разнарядка».</div>' : ''}
    ${shiftStarted(a) && a.basis && !a.keys_issued ? '<div class="inline-error">Смена продолжается по новой разнарядке. Дождитесь выдачи ключей от новой машины.</div>' : ''}
    ${trips.map(trip => tripCard(a,trip,activeId)).join('') || '<div class="empty">Ездок пока нет.</div>'}
    ${a.status !== 'Заменена' && !a.shift_closed ? dispatcherButton('Проблема с рейсом — позвонить диспетчеру') : ''}</section>`;
}
function renderHistory() {
  const events = [...state.events].sort((a,b) => b.time.localeCompare(a.time));
  main.innerHTML = `<section class="page">${events.map(e => `<div class="history-entry ${e.error ? 'failed' : !e.sent ? 'pending' : ''}"><strong class="${e.cancelled ? 'cancelled' : ''}">${escapeHTML(eventLabels[e.type] || e.type)}</strong><small>${escapeHTML(shortTime(e.time))}</small><small>${e.geo?.latitude != null && e.geo?.longitude != null ? 'Геометка записана' : 'Геометка не записана'}</small>${e.geo?.fix_time ? `<small>Координаты определены: ${escapeHTML(shortTime(e.geo.fix_time))}</small>` : ''}<small class="${!e.cancelled && !e.sent ? 'inline-error' : ''}">${e.cancelled ? 'отменено водителем' : e.sent ? 'отправлено' : 'в очереди'}</small>${e.error ? `<small class="inline-error">1С отклонила: ${escapeHTML(e.error)}</small>` : ''}</div>`).join('') || '<div class="empty">Событий пока нет</div>'}</section>`;
}
function renderSettings() {
  const a = activeAssignment();
  const storedName = localStorage.getItem(`arm_driver_name_${prefix || 'test3'}_${state.driverId}`);
  main.innerHTML = `<section class="page"><h2 class="screen-title">Настройки</h2>
    <div class="fields">${field('Водитель',storedName || a?.driver?.name || '—')}${!storedName && !a?.driver?.name ? '<p class="muted-text">ФИО появится с первой разнарядкой. Проверьте QR водителя, если разнарядки уже приходили.</p>' : ''}${a?.vehicle ? field('Машина',[a.vehicle.name,a.vehicle.plate].filter(Boolean).join(' ')) : ''}${field('Контур',prefix === '/prod' ? 'Рабочая база' : 'Тестовая база test3')}${field('Версия приложения','веб · 2026.11.18.1')}</div>
    ${dispatcherButton()}
    <h2 class="section-label">Уведомления</h2><p class="subtitle">Добавьте сайт на экран «Домой», затем разрешите уведомления на iPhone.</p><button class="button secondary wide" data-action="push-enable">Включить уведомления</button>
    <button class="button secondary wide" data-action="check-update">Проверить обновления</button>
    <button class="service-link" data-action="service-open">Служебный вход</button></section>`;
}

async function geoAtTap() {
  if (!navigator.geolocation) return null;
  return new Promise(resolve => navigator.geolocation.getCurrentPosition(position => resolve({
    latitude:position.coords.latitude, longitude:position.coords.longitude,
    accuracy:Math.round(position.coords.accuracy), fix_time:timestamp()
  }), error => { console.warn('Геометка недоступна', error); flash('Геометка недоступна. Отметка будет отправлена без координат.'); resolve(null); },
  { enableHighAccuracy:true, timeout:4000, maximumAge:10000 }));
}
async function eventMoment() {
  const id = crypto.randomUUID(), time = timestamp();
  return { id, time, geo:await geoAtTap() };
}
async function enqueue(a, type, tripId = null, extra = {}, photos = [], moment = null) {
  const id = moment?.id || crypto.randomUUID(), time = moment?.time || timestamp();
  const duplicate = state.events.some(e => !e.cancelled && e.assignment_id === a.id && e.type === type && e.trip_id === tripId &&
    (type === 'Ознакомление' || type === 'Срыв' || type === 'ПрибылНаРазгрузку' ? e.assignment_version === a.version : true));
  if (duplicate) { flash('Эта отметка уже записана'); return; }
  const event = { id, type, driver_id:state.driverId, assignment_id:a.id, trip_id:tripId, time, assignment_version:a.version, sent:false,
    send_after: tripId && stages.includes(type) && !photos.length ? Date.now() + 5000 : 0,
    comment:extra.comment || '', reason:extra.reason || undefined, reason_code:extra.reason_code || undefined,
    geo:moment ? moment.geo : await geoAtTap() };
  const records = photos.map(photo => ({id:crypto.randomUUID(),driver_id:state.driverId,assignment_id:a.id,trip_id:tripId,event_id:id,kind:photo.kind,blob:photo.blob,sent:false}));
  await putAction(state.db,event,records);
  await reloadLocal(); render(); flash('Отметка записана');
  scheduleHeldFlush();
  await flush();
}
async function flush() {
  if (state.syncing) { state.flushAgain = true; return; }
  if (!state.db || !navigator.onLine) return;
  state.syncing = true;
  let expired = false;
  try {
    try {
      const events = state.events.filter(e => !e.sent && !e.cancelled && !e.error && (!e.send_after || e.send_after <= Date.now()));
      if (events.length) {
        const response = await request('/events', { method:'POST', body:JSON.stringify({ events:events.map(({sent,error,assignment_version,cancelled,send_after,...e}) => e) }) });
        for (const result of response.events || []) {
          const event = events.find(e => e.id === result.id);
          if (!event) continue;
          await put(state.db, 'events', {...event, sent:!!result.accepted, error:result.accepted ? '' : result.error || '1С не приняла событие'});
        }
      }
    } catch (error) {
      if (error.status === 401) expired = true;
      else state.lastError = errorText(error);
    }
    if (!expired) { await reloadLocal(); scheduleHeldFlush(); }
    for (const photo of expired ? [] : state.photos.filter(p => !p.sent && !p.error)) {
      try {
        const form = new FormData();
        for (const field of ['id','driver_id','assignment_id','trip_id','event_id','kind']) form.set(field === 'id' ? 'photo_id' : field, photo[field]);
        form.set('photo', photo.blob, `${photo.id}.jpg`);
        await request('/documents', { method:'POST', body:form });
        await put(state.db, 'photos', {...photo, sent:true, blob:null, error:''});
      } catch (error) {
        if (error.status === 401) { expired = true; break; }
        await put(state.db, 'photos', {...photo,error:errorText(error)});
      }
    }
    if (!expired) await reloadLocal();
  } finally {
    state.syncing = false;
    if (expired) { state.flushAgain = false; expireSession(); }
    else {
      render();
      if (state.flushAgain) { state.flushAgain = false; await flush(); }
    }
  }
}
async function retryPending() {
  for (const event of state.events.filter(e => !e.sent && e.error)) await put(state.db,'events',{...event,error:''});
  for (const photo of state.photos.filter(p => p.error)) await put(state.db,'photos',{...photo,error:''});
  state.lastError = ''; await reloadLocal(); await flush();
}

function showModal(html) { modal.innerHTML = `<div class="modal" role="dialog" aria-modal="true">${html}</div>`; modal.hidden = false; }
function closeModal() {
  if (pairScanner) {
    pairScanner.active = false;
    cancelAnimationFrame(pairScanner.frame);
    pairScanner.stream?.getTracks().forEach(track => track.stop());
    pairScanner = null;
  }
  modal.hidden = true; modal.innerHTML = '';
}
function selectedAssignment(id) { return state.assignments.find(a => a.id === id); }
function selectedTrip(a,id) { return a?.trips.find(t => t.id === id); }

function capturePhoto(stream, prompt, index, total) {
  return new Promise(resolve => {
    showModal(`<h2>Шаг ${index + 1} из ${total}</h2><p class="subtitle">${escapeHTML(prompt.label)}<br>${escapeHTML(prompt.hint)}</p><video class="camera-video" autoplay playsinline muted></video><img class="camera-preview" alt="Снимок" hidden><button class="button wide large" id="take-photo">Снять фото</button><div id="review-photo" hidden><p>Проверьте снимок. Если смазано или не видно — переснимите.</p><div class="button-row"><button class="button secondary" id="retake-photo">Переснять</button><button class="button" id="accept-photo">Подтвердить</button></div>${prompt.allowMore ? '<button class="button secondary wide" id="more-photo">Сохранить и снять ещё</button>' : ''}</div><button class="button ghost wide" id="cancel-photo">Выйти из камеры</button>`);
    const video = modal.querySelector('video');
    const preview = modal.querySelector('.camera-preview');
    const take = modal.querySelector('#take-photo');
    const review = modal.querySelector('#review-photo');
    let blob = null, previewURL = null;
    const clean = () => { if (previewURL) URL.revokeObjectURL(previewURL); };
    let settled = false;
    const onHidden = () => { if (document.hidden) done(null); };
    const done = value => {
      if (settled) return;
      settled = true;
      document.removeEventListener('visibilitychange',onHidden);
      clean(); resolve(value);
    };
    document.addEventListener('visibilitychange',onHidden);
    video.srcObject = stream;
    video.play().catch(error => flash(errorText(error)));
    modal.querySelector('#cancel-photo').onclick = () => done(null);
    modal.querySelector('#retake-photo').onclick = () => { clean(); previewURL=null;blob=null;preview.hidden=true;review.hidden=true;video.hidden=false;take.hidden=false; };
    modal.querySelector('#accept-photo').onclick = () => done({blob,more:false});
    if (prompt.allowMore) modal.querySelector('#more-photo').onclick = () => done({blob,more:true});
    take.onclick = async () => {
      try {
        const canvas = document.createElement('canvas');
        const scale = Math.min(1, 2000 / Math.max(video.videoWidth,video.videoHeight));
        canvas.width = Math.round(video.videoWidth * scale); canvas.height = Math.round(video.videoHeight * scale);
        if (!canvas.width || !canvas.height) throw new Error('Камера ещё запускается');
        canvas.getContext('2d').drawImage(video,0,0,canvas.width,canvas.height);
        let quality = .9;
        do { blob = await new Promise(doneBlob => canvas.toBlob(doneBlob,'image/jpeg',quality)); quality -= .1; }
        while (blob?.size > 1_500_000 && quality >= .4);
        if (!blob) throw new Error('Не удалось сохранить фото');
        previewURL = URL.createObjectURL(blob);
        preview.src = previewURL; preview.hidden=false;review.hidden=false;video.hidden=true;take.hidden=true;
      } catch (error) { flash(errorText(error)); }
    };
  });
}

function missingPhotoDecision(missing, cameraDenied) {
  return new Promise(resolve => {
    showModal(`<h2>Не все фото сделаны</h2>${cameraDenied ? '<p class="inline-error">Нет доступа к камере. Разрешите её в настройках телефона.</p>' : ''}<p>Осталось снять: ${missing.map(step => escapeHTML(step.short || step.label)).join(', ')}.</p><p>Если снять нельзя — укажите причину, этап отметится без недостающих фото.</p><label class="field"><span>Причина (обязательно)</span><textarea id="missing-reason" maxlength="300"></textarea></label><button class="button wide" id="continue-photo">Продолжить съёмку</button><button class="button secondary wide" id="without-photo" disabled>Без недостающих</button><button class="button ghost wide" id="abort-photo">Отменить этап</button>`);
    const reason = modal.querySelector('#missing-reason');
    const without = modal.querySelector('#without-photo');
    reason.oninput = () => { without.disabled = !reason.value.trim(); };
    modal.querySelector('#continue-photo').onclick = () => resolve({action:'continue'});
    without.onclick = () => resolve({action:'without',reason:reason.value.trim()});
    modal.querySelector('#abort-photo').onclick = () => resolve({action:'abort'});
  });
}

async function photoFlow(prompts, allowMissing = false) {
  const photos = [];
  let stream;
  try {
    for (let index = 0; index < prompts.length;) {
      if (!stream) {
        try { stream = await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'}},audio:false}); }
        catch (error) {
          if (!allowMissing) throw error;
          const decision = await missingPhotoDecision(prompts.slice(index), true);
          if (decision.action === 'continue') continue;
          return decision.action === 'without' ? {photos,missingReason:decision.reason} : {aborted:true};
        }
      }
      const result = await capturePhoto(stream,prompts[index],index,prompts.length);
      if (!result) {
        if (!allowMissing) return {aborted:true};
        const decision = await missingPhotoDecision(prompts.slice(index),false);
        if (decision.action === 'continue') continue;
        return decision.action === 'without' ? {photos,missingReason:decision.reason} : {aborted:true};
      }
      photos.push({kind:prompts[index].kind,blob:result.blob});
      if (!result.more) index++;
    }
    return {photos,missingReason:null};
  } finally { stream?.getTracks().forEach(track => track.stop()); closeModal(); }
}
const photoPrompts = {
  ЗагрузилсяВПуть:[{kind:'truck_rear',short:'грузовик сзади',label:'Сфотографируйте грузовик сзади',hint:'Кузов и госномер целиком в кадре'},{kind:'truck_side',short:'грузовик сбоку',label:'Сфотографируйте грузовик сбоку',hint:'Машина целиком, вместе с грузом'}],
  Разгрузился:[{kind:'truck_rear',short:'грузовик сзади',label:'Сфотографируйте грузовик сзади',hint:'Кузов и госномер целиком в кадре'},{kind:'truck_side',short:'грузовик сбоку',label:'Сфотографируйте грузовик сбоку',hint:'Машина целиком в кадре'},{kind:'document',short:'документ',label:'Сфотографируйте документ',hint:'Весь лист в кадре, подпись и печать должны читаться',allowMore:true}]
};
async function doStep(a,trip,type) {
  if (state.busy) return; state.busy = true;
  try {
    let comment = '';
    if (type === 'Разгрузился') {
      const answer = await askComment('Разгрузка');
      if (answer === null) return;
      comment = answer;
    }
    const result = photoPrompts[type] ? await photoFlow(photoPrompts[type],true) : {photos:[]};
    if (result.aborted) return;
    if (result.missingReason) comment = [comment,`${result.photos.length ? 'Не все фото' : 'Без фото'}: ${result.missingReason}`].filter(Boolean).join('; ').slice(0,500);
    const moment = await eventMoment();
    await enqueue(a,type,trip.id,{comment},result.photos,moment);
  } catch (error) { flash(errorText(error)); }
  finally { state.busy = false; }
}
function askComment(title) {
  return new Promise(resolve => {
    showModal(`<h2>${escapeHTML(title)}</h2><p>Если клиент принял только часть груза, укажите в комментарии, сколько не приняли.</p><label class="field"><span>Комментарий (необязательно)</span><textarea id="comment" maxlength="500"></textarea></label><button class="button wide" id="comment-ok">Продолжить к фото</button><button class="button ghost wide" id="comment-cancel">Отмена</button>`);
    modal.querySelector('#comment-ok').onclick = () => { const text=modal.querySelector('#comment').value.trim();closeModal();resolve(text); };
    modal.querySelector('#comment-cancel').onclick = () => {closeModal();resolve(null);};
  });
}
function failureFlow(a,trip,draft = {photos:[]}) {
  const progress = tripProgress(a,trip);
  const client = progress.done.has('ПрибылНаРазгрузку') && !progress.done.has('Разгрузился') && !trip.return_to_depot;
  const options = client ? [['КлиентНеГотов','Клиент не готов принять (время не согласовано)'],['Качество','Претензия к качеству'],['Прочее','Другая причина']] :
    [['','Поломка машины'],['','Нет подъезда'],['','Груз не помещается'],['','Другое']];
  showModal(`<h2>${client ? 'Клиент не принял груз' : 'Не могу выполнить рейс'}</h2><p class="subtitle">${client ? 'Выберите причину отказа. Рейс останется невыполненным до решения диспетчера.' : 'Выберите причину или напишите свою. Рейс останется в списке как невыполненный.'}</p>
    <form id="fail-form"><label class="field"><span>Причина</span><select id="fail-choice" required><option value="">Выберите причину</option>${options.map(([code,text],i) => `<option value="${i}" ${draft.choiceIndex === i ? 'selected' : ''}>${escapeHTML(text)}</option>`).join('')}</select></label>
    <label class="field"><span>Причина (обязательно)</span><input id="fail-reason" value="${escapeHTML(draft.reason || '')}" maxlength="200" required></label><label class="field"><span>Комментарий диспетчеру</span><textarea id="fail-comment" maxlength="500">${escapeHTML(draft.comment || '')}</textarea></label>
    ${client ? `<button type="button" class="button secondary wide" id="fail-capture" ${draft.choiceIndex == null ? 'hidden' : ''}>Сфотографировать груз${draft.photos.length ? ` · ${draft.photos.length}` : ''}</button>` : ''}<div id="fail-photo-note"></div><button class="button wide large">Подтвердить</button><button type="button" class="button ghost wide" id="fail-cancel">Отмена</button></form>`);
  const form = modal.querySelector('#fail-form');
  const choiceAt = () => form.querySelector('#fail-choice').value === '' ? null : Number(form.querySelector('#fail-choice').value);
  const currentDraft = () => ({choiceIndex:choiceAt(),reason:form.querySelector('#fail-reason').value,comment:form.querySelector('#fail-comment').value,photos:draft.photos});
  form.querySelector('#fail-choice').onchange = () => {
    const value=form.querySelector('#fail-choice').value;
    const selected=value === '' ? null : options[Number(value)];
    form.querySelector('#fail-reason').value = selected ? selected[1] === 'Другое' || selected[0] === 'Прочее' ? '' : client ? `Клиент не принимает: ${selected[1]}` : selected[1] : '';
    form.querySelector('#fail-photo-note').textContent = selected?.[0] === 'Качество' ? 'При претензии к качеству нужно хотя бы одно фото груза.' : '';
    if (client) form.querySelector('#fail-capture').hidden = !selected;
  };
  if (draft.choiceIndex != null && options[draft.choiceIndex]?.[0] === 'Качество') form.querySelector('#fail-photo-note').textContent = 'При претензии к качеству нужно хотя бы одно фото груза.';
  if (client) form.querySelector('#fail-capture').onclick = async () => {
    if (state.busy) return;
    const saved = currentDraft();
    state.busy=true;
    try {
      const result = await photoFlow([{kind:'cargo',label:'Сфотографируйте груз',hint:'Покажите причину отказа клиента',allowMore:true}]);
      failureFlow(a,trip,{...saved,photos:[...saved.photos,...(result.photos || [])]});
    } catch (error) { failureFlow(a,trip,saved); flash(errorText(error)); }
    finally { state.busy=false; }
  };
  form.querySelector('#fail-cancel').onclick = closeModal;
  form.onsubmit = async event => {
    event.preventDefault();
    if (state.busy) return;
    const value=form.querySelector('#fail-choice').value;
    const choice = value === '' ? null : options[Number(value)];
    if (!choice) return;
    const reason=form.querySelector('#fail-reason').value.trim(),comment=form.querySelector('#fail-comment').value.trim();
    if (!reason || reason.length>200 || choice[0]==='Прочее' && !comment) { flash('Укажите причину и комментарий');return; }
    if (choice[0]==='Качество' && !draft.photos.length) { flash('При претензии к качеству нужно фото груза'); return; }
    state.busy=true;
    try {
      const moment = await eventMoment();
      const code=choice[0] || undefined; closeModal();
      await enqueue(a,'Срыв',trip.id,{reason,comment,reason_code:code},draft.photos,moment);
    } catch (error) { flash(errorText(error)); }
    finally { state.busy=false; }
  };
}

function decodeApplicationKey(value) {
  const base64 = value.replace(/-/g,'+').replace(/_/g,'/');
  const binary = atob(base64 + '='.repeat((4-base64.length%4)%4));
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}
async function enablePush() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
    flash('Этот iPhone не поддерживает веб-уведомления'); return;
  }
  if (!window.navigator.standalone && !matchMedia('(display-mode: standalone)').matches) {
    flash('Сначала добавьте сайт на экран «Домой» и откройте его с иконки'); return;
  }
  // Запрос разрешения должен начаться непосредственно после нажатия кнопки.
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') { flash('Разрешение на уведомления не получено'); return; }
  const response = await request('/push/key');
  const registration = await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:decodeApplicationKey(response.public_key)});
  await request('/push/subscriptions',{method:'POST',body:JSON.stringify(subscription.toJSON())});
  flash('Уведомления включены'); render();
}
document.addEventListener('click', async event => {
  const target = event.target.closest('[data-action],[data-screen]');
  if (!target) return;
  if (target.dataset.screen) { state.serviceOpen=false; state.screen=target.dataset.screen; render(); window.scrollTo(0,0); return; }
  const action=target.dataset.action, a=selectedAssignment(target.dataset.id || target.dataset.assignment), trip=selectedTrip(a,target.dataset.trip);
  try {
    if (action==='service-open') { state.serviceOpen=true; renderService(); return; }
    if (action==='service-close') { state.serviceOpen=false; state.driverId ? render() : renderUnconfigured(); return; }
    if (action==='clear-assignments' && state.db) {
      if (pendingItems().length) { flash('Сначала отправьте очередь событий и фото.'); return; }
      await clearAssignments(state.db);
      await reloadLocal();
      state.selectedId=null;
      flash('Разнарядки на телефоне очищены. События и фото сохранены.');
      await refreshAssignments();
      return;
    }
    if (action==='refresh') await refreshAssignments();
    if (action==='retry') await retryPending();
    if (action==='open-assignment' && a) {state.selectedId=a.id;state.assignmentDetail=true;render();window.scrollTo(0,0);}
    if (action==='back-assignments') {state.assignmentDetail=false;render();window.scrollTo(0,0);}
    if (action==='open-new-assignment' && a) {state.dismissedNewAssignmentId=a.id;state.newAssignmentNoticeId=null;closeModal();state.selectedId=a.id;state.assignmentDetail=true;state.screen='assignments';render();window.scrollTo(0,0);}
    if (action==='dismiss-new-assignment') {state.dismissedNewAssignmentId=state.newAssignmentNoticeId;closeModal();render();}
    if (action==='open-reack' && a) {state.dismissedReackVersion=`${a.id}:${a.version}`;closeModal();state.selectedId=a.id;state.assignmentDetail=true;state.screen='assignments';render();window.scrollTo(0,0);}
    if (action==='dismiss-reack' && a) {state.dismissedReackVersion=`${a.id}:${a.version}`;closeModal();render();}
    if (action==='show-trips') {state.selectedId=a.id;state.screen='trips';render();window.scrollTo(0,0);}
    if (action==='toggle-trip') {state.expandedTripId=state.expandedTripId===target.dataset.trip?null:target.dataset.trip;render();}
    if (action==='ack' && a && !acknowledged(a) && a.status!=='Заменена') await enqueue(a,'Ознакомление');
    if (action==='start' && a && acknowledged(a) && a.keys_issued && a.departure_day===currentDate() && !shiftStarted(a) &&
      !(a.basis && state.assignments.some(previous => previous.id === a.basis.id && previous.driver?.id === a.driver?.id)) &&
      !state.assignments.some(other => other.id !== a.id && shiftStarted(other) && !other.shift_closed && other.status !== 'Заменена' && other.status !== 'Отменена')) {
      await enqueue(a,'НачалоСмены');
    }
    if (action==='trip-step' && a && trip && canWork(a) && tripProgress(a,trip).next===target.dataset.type) await doStep(a,trip,target.dataset.type);
    if (action==='trip-fail' && a && trip && canWork(a) && !tripProgress(a,trip).resolved) failureFlow(a,trip);
    if (action==='trip-undo' && a && trip) {
      const eventToCancel = undoableEvent(a.id, trip.id);
      if (eventToCancel) {
        await cancelAction(state.db, eventToCancel, state.photos.filter(p => p.event_id === eventToCancel.id));
        await reloadLocal(); render(); flash('Последняя отметка отменена');
      }
    }
    if (action==='push-enable') await enablePush();
    if (action==='reload-app') location.reload();
    if (action==='check-update') {
      if ('serviceWorker' in navigator) await navigator.serviceWorker.ready.then(registration => registration.update());
      location.reload();
    }
  } catch (error) { flash(errorText(error)); }
});

start();
