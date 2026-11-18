import { openDriverDb, all, put, putAction, cancelAction } from './db.js';

const prefix = location.pathname.split('/web')[0];
const apiRoot = `${prefix}/api/web`;
const stages = ['ПрибылНаПогрузку', 'ЗагрузилсяВПуть', 'ПрибылНаРазгрузку', 'Разгрузился'];
const stageLabels = ['Прибыл на погрузку', 'Загрузился, в пути', 'Прибыл на разгрузку', 'Разгрузился'];
const stageFields = ['arrived_load', 'loaded', 'arrived_unload', 'unloaded'];
const main = document.querySelector('#main');
const nav = document.querySelector('#nav');
const modal = document.querySelector('#modal');
const toast = document.querySelector('#toast');
const connection = document.querySelector('#connection');
const environment = document.querySelector('#environment');
const currentBar = document.querySelector('#current-bar');
const queueBanner = document.querySelector('#queue-banner');
environment.textContent = 'ТЕСТОВАЯ БАЗА';
environment.hidden = prefix === '/prod';

const state = { driverId: null, db: null, assignments: [], events: [], photos: [], screen: 'assignments', selectedId: null, assignmentDetail: false, expandedTripId: null, newAssignmentNoticeId: null, refreshing: false, syncing: false, flushAgain: false, busy: false, lastError: '' };

const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const label = value => escapeHTML(value || '—');
const currentDate = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Kirov', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
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
  if (a.status === 'Отменена') return 'Отменена диспетчером';
  if (shiftStarted(a)) return 'Смена идёт';
  if (acknowledged(a)) return 'Принята';
  return assignmentEvents(a.id).some(e => e.type === 'Ознакомление') ? 'Изменена' : 'Не принята';
}
function activeAssignment() {
  const list = [...state.assignments];
  const working = list.filter(a => shiftStarted(a) && !a.shift_closed && a.status !== 'Заменена');
  const inherited = working.filter(a => a.basis && a.shift_started);
  if (inherited.length) return inherited.sort((a,b) => b.departure_day.localeCompare(a.departure_day))[0];
  if (working.length) return working.sort((a,b) => (b.shift_started_at || '').localeCompare(a.shift_started_at || ''))[0];
  const linked = list.filter(a => a.status === 'Заменена').map(a => list.find(b => b.id === a.replacement?.id)).find(Boolean);
  if (linked) return linked;
  return list.filter(a => !a.shift_closed && a.status !== 'Заменена' && a.status !== 'Отменена')
    .sort((a,b) => a.departure_day.localeCompare(b.departure_day) || String(a.number).localeCompare(String(b.number)))[0] || null;
}
function tripProgress(a, trip) {
  const local = assignmentEvents(a.id).filter(e => e.trip_id === trip.id);
  const previousFailure = local.filter(e => e.type === 'Срыв' && e.sent && e.assignment_version < a.version).sort((x,y) => y.time.localeCompare(x.time))[0];
  const resetAfterFailure = previousFailure && trip.status === 'Назначена';
  const effective = local.filter(e => !resetAfterFailure || !(e.type === 'Срыв' && e.sent && e.assignment_version < a.version) && !(e.type === 'ПрибылНаРазгрузку' && e.time <= previousFailure.time));
  const done = new Set(effective.map(e => e.type));
  stageFields.forEach((field, i) => { if (trip.stages?.[field]) done.add(stages[i]); });
  const inactive = !['Назначена', 'Активна'].includes(trip.status);
  const resolved = inactive || done.has('Разгрузился') || done.has('Срыв') || trip.next_stage === '';
  const index = trip.next_stage == null ? 0 : stages.indexOf(trip.next_stage);
  const next = resolved || index < 0 ? null : stages.slice(index).find(type => !done.has(type)) || null;
  return { done, resolved, next, invalidNext:!resolved && index < 0, failed: done.has('Срыв') || trip.status === 'Сорвана', returned: !!resetAfterFailure };
}
function routeSignature(a) {
  return JSON.stringify({ number:a.number, day:a.departure_day, status:a.status, driver:a.driver, vehicle:a.vehicle,
    trips:a.trips.map(t => ({ id:t.id, order:t.order, status:t.status === 'Сорвана' ? 'Назначена' : t.status,
      customer:t.customer, trailer:t.trailer, load:t.load_point, unload:t.unload_point, cargo:t.cargo, contacts:t.contacts,
      return_to_depot:t.return_to_depot })) });
}
function mergeAssignment(previous, fresh) {
  if (previous && fresh.version <= previous.version) return previous;
  const ackRequired = !previous ? fresh.version : routeSignature(previous) !== routeSignature(fresh) ? fresh.version : previous.ack_required_version || previous.version;
  const inherited = previous?.inherited_ack_version || (fresh.basis && fresh.shift_started ? fresh.version : 0);
  return { ...fresh, ack_required_version:ackRequired, inherited_ack_version:inherited };
}
function currentSelection() {
  const selected = selectedAssignment(state.selectedId);
  if (selected?.status === 'Заменена') return selectedAssignment(selected.replacement?.id) || activeAssignment() || selected;
  return selected || activeAssignment();
}

async function reloadLocal() {
  [state.assignments, state.events, state.photos] = await Promise.all(['assignments','events','photos'].map(name => all(state.db, name)));
  state.assignments.sort((a,b) => b.departure_day.localeCompare(a.departure_day) || b.version - a.version);
}
async function refreshAssignments() {
  if (state.refreshing) return;
  state.refreshing = true;
  try {
    const response = await request('/assignments');
    const known = new Set(state.assignments.map(a => a.id));
    for (const item of response.assignments || []) {
      const previous = state.assignments.find(a => a.id === item.id);
      const merged = mergeAssignment(previous, item);
      if (merged !== previous) await put(state.db, 'assignments', merged);
    }
    state.lastError = '';
    await reloadLocal();
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
  localStorage.setItem(`arm_driver_last_${prefix || 'test3'}`, driverId);
  state.db?.close();
  state.db = await openDriverDb(prefix || 'test3', driverId);
  await reloadLocal();
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
  document.addEventListener('visibilitychange', () => { if (!document.hidden && state.driverId) { refreshAssignments(); flush(); } });
  navigator.serviceWorker?.addEventListener('message', event => {
    if (event.data?.type === 'assignment-updated' && state.driverId) refreshAssignments();
  });
  connection.textContent = navigator.onLine ? '' : 'Нет сети. Неотправленные действия остаются на этом iPhone.';
  try {
    const session = await request('/session');
    await initDriver(session.driver_id);
  } catch (error) {
    const last = localStorage.getItem(`arm_driver_last_${prefix || 'test3'}`);
    if (error.status !== 401 && last) {
      await initDriver(last);
      state.lastError = 'Сервер недоступен: данные взяты из памяти iPhone';
      render();
    } else renderPair();
  }
}

function renderPair() {
  nav.hidden = true;
  currentBar.hidden = true;
  queueBanner.innerHTML = '';
  const url = new URL(location.href);
  const code = url.searchParams.get('pair') || '';
  if (code) { url.searchParams.delete('pair'); history.replaceState(null, '', url); }
  main.innerHTML = `<section class="page pair-wrap"><div class="app-header"><img src="brand.svg" alt="">АРМ водителя</div><h1 class="section-title">Привязка iPhone</h1><p class="subtitle">Введите одноразовый код, выданный сотрудником.</p>
    <form id="pair-form" class="card"><label class="field"><span>Код привязки</span><input name="code" value="${escapeHTML(code)}" autocomplete="one-time-code" autocapitalize="off" spellcheck="false" maxlength="24" required></label><button class="button wide large">Привязать телефон</button></form>
    <div class="notice">Для уведомлений откройте сайт в Safari и выберите «Поделиться» → «На экран Домой». Затем запустите приложение с иконки.</div></section>`;
  document.querySelector('#pair-form').onsubmit = async event => {
    event.preventDefault();
    const button = event.target.querySelector('button'); button.disabled = true;
    try {
      const code = event.target.elements.code.value.trim();
      const session = await request('/pair', {method:'POST', body:JSON.stringify({code})});
      await initDriver(session.driver_id);
      flash('Телефон привязан');
    } catch (error) { flash(errorText(error)); button.disabled = false; }
  };
}
function expireSession() {
  state.db?.close();
  state.db = null;
  state.driverId = null;
  renderPair();
  flash('Срок привязки истёк. Получите новый код; сохранённая очередь останется на этом iPhone.');
}
function renderNav() {
  const entries = [['assignments','assignment','Разнарядка'],['trips','truck','Мои рейсы'],['history','history','История'],['settings','settings','Настройки']];
  nav.innerHTML = entries.map(([key,icon,title]) => `<button type="button" data-screen="${key}" class="${state.screen === key ? 'selected' : ''}"><svg aria-hidden="true"><use href="icons.svg#${icon}"></use></svg><span>${title}</span></button>`).join('');
}
function render() {
  if (!state.driverId) return renderPair();
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
  renderNav();
  if (state.screen === 'assignments') renderAssignments();
  if (state.screen === 'trips') renderTrips();
  if (state.screen === 'history') renderHistory();
  if (state.screen === 'settings') renderSettings();
  if (state.newAssignmentNoticeId && modal.hidden && !state.busy) {
    const added = selectedAssignment(state.newAssignmentNoticeId);
    if (added) showModal(`<h2>Новая разнарядка № ${label(added.number || added.id)}</h2><p>Проверьте маршрут и машину. Разнарядка уже доступна в приложении.</p><button class="button wide large" data-action="open-new-assignment" data-id="${escapeHTML(added.id)}">Открыть разнарядку</button><button class="button secondary wide" data-action="dismiss-new-assignment">Позже</button>`);
  }
}
function field(name, value) { return `<div class="labeled-field"><span>${escapeHTML(name)}</span><strong>${label(value)}</strong></div>`; }
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
  const list = state.assignments;
  if (list.length === 1 || state.assignmentDetail && selectedAssignment(state.selectedId)) {
    main.innerHTML = renderSelectedAssignment();
    return;
  }
  if (!list.length) {
    main.innerHTML = `<section class="page assignment-list"><div class="empty">Разнарядок пока нет. Дождитесь отправки из 1С или обновите данные.</div><button class="button secondary wide" data-action="refresh">Обновить</button></section>`;
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
  const keyMessage = !a.keys_issued ? 'Ожидайте выдачи ключей механиком.' : a.departure_day !== currentDate() ? `Начать смену можно только в день выезда — ${a.departure_day}.` : '';
  const planTrips = !shift ? `<h2 class="section-label">Ездки по плану</h2><p class="subtitle">Это только просмотр. Отмечать этапы нужно после начала смены во вкладке «Мои рейсы».</p>${[...a.trips].sort((x,y) => x.order-y.order).map(t => `<div class="upcoming-trip"><strong>${t.order}. ${routeTitle(t.load_point)} → ${routeTitle(t.unload_point)}</strong>${t.status === 'Отменена' ? `<div class="inline-error">Рейс отменён${t.cancel_reason ? ': ' + escapeHTML(t.cancel_reason) : ''}</div>` : ''}${field('Погрузка', t.load_point?.address || t.load_point?.name)}${field('Разгрузка', t.unload_point?.address || t.unload_point?.name)}${t.trailer?.plate ? field('Прицеп',t.trailer.plate) : ''}</div>`).join('') || '<p>Ездки ещё не переданы</p>'}` : '';
  const total = a.shift_total ? cargoDetails(a.shift_total) : '<p>Итог за смену ещё не передан</p>';
  return `<section class="page"><div class="toolbar">${state.assignments.length > 1 ? '<button class="toolbar-back" data-action="back-assignments" aria-label="К списку">‹</button>' : '<span></span>'}<span class="status-pill ${status === 'Смена идёт' ? 'active' : ''}">${escapeHTML(status)}</span></div>
    <div class="app-header"><img src="brand.svg" alt="">АРМ водителя</div><h1 class="section-title">Разнарядка № ${label(a.number || a.id)}</h1>
    <div class="fields">${field('Дата выезда',a.departure_day)}${field('Водитель',a.driver?.name || a.driver?.id)}${field('Машина',[a.vehicle?.name,a.vehicle?.plate].filter(Boolean).join(' '))}${field('Рейсов на смену',a.trips.length)}</div>
    ${future ? `<div class="notice amber"><strong>${shortDate(a.departure_day) === 'Завтра' ? 'Разнарядка на завтра.' : 'Будущая разнарядка.'}</strong><br>Сначала посмотрите все ездки ниже, затем нажмите «Ознакомился и принял».</div>` : ''}
    ${planTrips}<h2 class="section-label">Итого за смену</h2>${total}
    ${a.cancel_reason ? `<div class="inline-error">${escapeHTML(a.cancel_reason)}</div>` : ''}
    ${a.status === 'Заменена' ? `<div class="notice red">Разнарядка заменена на № ${label(a.replacement?.number || a.replacement?.id)}. Рейсы этой разнарядки больше не отмечаются.</div>` : ''}
    ${a.status === 'Отменена' ? '<div class="notice red">Разнарядка отменена диспетчером. Дождитесь закрытия смены в 1С после осмотра медика.</div>' : ''}
    ${continuesSameDriver && !shift ? '<div class="notice red">Разнарядка назначена взамен прежней, но 1С ещё не передала «СменаНачата=true». Дождитесь обновления.</div>' : ''}
    ${continuesSameDriver && shift && !a.keys_issued ? '<div class="notice red">Смена продолжается по новой разнарядке. Дождитесь выдачи ключей от новой машины механиком.</div>' : ''}
    <div class="assignment-actions">${canAck ? `<button class="button wide large" data-action="ack" data-id="${escapeHTML(a.id)}">${shift ? 'Ознакомлен' : 'Ознакомился и принял'}</button>` : ''}
    ${!shift && a.status !== 'Заменена' && a.status !== 'Отменена' ? `<button class="button orange wide large" data-action="start" data-id="${escapeHTML(a.id)}" ${canStart ? '' : 'disabled'}>Начать смену</button>` : ''}
    ${!shift && ack && !canStart && keyMessage ? `<div class="inline-error">${escapeHTML(keyMessage)}</div>` : ''}
    ${!shift && otherOpen ? `<div class="inline-error">Сначала дождитесь закрытия смены по разнарядке № ${label(otherOpen.number || otherOpen.id)} в 1С.</div>` : ''}
    ${shift && !a.shift_closed && a.status !== 'Заменена' && a.status !== 'Отменена' && !(continuesSameDriver && !a.keys_issued) ? `<p>${a.keys_accepted ? 'Ключи сданы, ожидайте подтверждения медика и закрытия смены в 1С.' : 'После рейсов сдайте ключи механику и пройдите осмотр у медика. Смену закроет 1С.'}</p>` : ''}
    ${a.shift_closed ? '<p>Смена закрыта</p>' : ''}
    </div></section>`;
}
function canWork(a) { return shiftStarted(a) && !a.shift_closed && !['Заменена','Отменена'].includes(a.status) && a.keys_issued && acknowledged(a); }
function tripCard(a, trip, activeId) {
  const progress = tripProgress(a, trip);
  const complete = progress.done.has('Разгрузился');
  const failed = trip.status === 'Отменена' || trip.status === 'Сорвана' || progress.failed;
  const isActive = activeId === trip.id && !progress.resolved;
  const status = trip.status === 'Отменена' ? 'снят' : failed ? 'сорван' : complete ? 'выполнен' : isActive ? 'сейчас' : 'ожидает';
  const nextIndex = stages.indexOf(progress.next);
  const canFail = canWork(a) && !progress.resolved;
  const actionable = canWork(a) && isActive && progress.next;
  const expanded = state.expandedTripId === trip.id;
  const contacts = (trip.contacts || []).map(c => `<p>${label(c.role)}: ${label(c.name)} ${(c.phones || []).map(p => `<a href="tel:${encodeURIComponent(p.number || '')}">${label(p.display || p.number)}</a>`).join(', ')}</p>`).join('');
  const time = [planClock(trip.plan_load),planClock(trip.plan_unload)].filter(Boolean).join(' — ');
  return `<article class="trip-card ${isActive ? 'active' : ''} ${failed ? 'failed' : ''}"><button class="trip-summary" data-action="toggle-trip" data-trip="${escapeHTML(trip.id)}" aria-expanded="${expanded}"><span class="trip-index ${complete ? 'done' : failed ? 'failed' : ''}">${complete ? '✓' : failed ? '×' : trip.order}</span><span class="trip-head-text"><span class="trip-route">${routeTitle(trip.load_point)} → ${routeTitle(trip.unload_point)}</span><span class="trip-meta"><strong>${status}</strong>${time ? ` · ${escapeHTML(time)}` : ''}</span></span></button>
    ${trip.return_to_depot ? `<div class="trip-notice">Возврат на завод: ${routeTitle(trip.unload_point)}</div>` : ''}
    ${trip.cancel_reason ? `<div class="trip-notice">${escapeHTML(trip.cancel_reason)}</div>` : ''}
    ${progress.returned ? `<div class="trip-notice">Рейс возвращён: новый адрес ${routeTitle(trip.unload_point)}</div>` : ''}
    ${trip.trailer?.plate ? field('Прицеп',trip.trailer.plate) : ''}
    ${cargoDetails(trip.cargo)}
    <div class="trip-body" ${expanded ? '' : 'hidden'}>${field('Погрузка',trip.load_point?.address || trip.load_point?.name)}${field('Разгрузка',trip.unload_point?.address || trip.unload_point?.name)}${trip.customer ? field('Заказчик',trip.customer) : ''}${contacts}
    ${trip.status === 'Отменена' ? '<div class="inline-error">Рейс отменён</div>' : ''}
    ${complete ? '<div class="notice active">Рейс выполнен</div>' : ''}
    ${failed && trip.status !== 'Отменена' ? '<div class="inline-error">Не выполнена</div>' : ''}
    ${progress.invalidNext ? `<div class="inline-error">Неизвестный следующий этап от 1С: ${label(trip.next_stage)}. Сообщите диспетчеру.</div>` : ''}
    ${canFail ? `<button class="button secondary wide" data-action="trip-fail" data-assignment="${escapeHTML(a.id)}" data-trip="${escapeHTML(trip.id)}">Не могу выполнить рейс</button>` : ''}
    ${actionable ? `<button class="button orange wide large" data-action="trip-step" data-assignment="${escapeHTML(a.id)}" data-trip="${escapeHTML(trip.id)}" data-type="${escapeHTML(progress.next)}">${escapeHTML(stageLabels[nextIndex])}</button>` : ''}
    ${undoableEvent(a.id,trip.id) ? `<button class="button secondary wide" data-action="trip-undo" data-assignment="${escapeHTML(a.id)}" data-trip="${escapeHTML(trip.id)}">Отмена</button>` : ''}
    ${!canWork(a) && !progress.resolved ? `<p class="muted-text">${!shiftStarted(a) ? 'Начните смену, чтобы отмечать этапы этого рейса.' : !a.keys_issued ? 'Дождитесь выдачи ключей от новой машины.' : !acknowledged(a) ? 'Проверьте маршрут и нажмите «Ознакомлен» на вкладке «Разнарядка».' : 'Рейс сейчас недоступен.'}</p>` : ''}
    ${canWork(a) && !isActive && !progress.resolved ? '<p class="muted-text">Рейс станет доступен, когда закроется текущий.</p>' : ''}</div></article>`;
}
function renderTrips() {
  const a = currentSelection();
  if (!a) { main.innerHTML = `<section class="page roadmap-page"><h2 class="screen-title">Мои рейсы</h2><div class="empty">Нет разнарядки.</div></section>`; return; }
  state.selectedId = a.id;
  const trips = [...a.trips].sort((x,y) => x.order - y.order);
  const activeId = trips.find(t => { const p = tripProgress(a,t); return !p.resolved && !p.returned; })?.id || trips.find(t => !tripProgress(a,t).resolved)?.id;
  const needsAck = !acknowledged(a) && shiftStarted(a);
  main.innerHTML = `<section class="page roadmap-page"><h2 class="screen-title">Разнарядка № ${label(a.number || a.id)}</h2>
    ${needsAck ? '<div class="inline-error">Маршрут изменён. Откройте разнарядку и нажмите «Ознакомлен».</div>' : ''}
    ${a.status === 'Заменена' ? '<div class="inline-error">Разнарядка заменена. Выберите новую в списке.</div>' : ''}
    ${!shiftStarted(a) ? '<div class="inline-error">Смена не начата. Отметить рейс можно после «Начать смену» на вкладке «Разнарядка».</div>' : ''}
    ${shiftStarted(a) && a.basis && !a.keys_issued ? '<div class="inline-error">Смена продолжается по новой разнарядке. Дождитесь выдачи ключей от новой машины.</div>' : ''}
    ${trips.map(trip => tripCard(a,trip,activeId)).join('') || '<div class="empty">Ездок пока нет.</div>'}</section>`;
}
function renderHistory() {
  const events = [...state.events].sort((a,b) => b.time.localeCompare(a.time));
  main.innerHTML = `<section class="page"><h2 class="screen-title">История</h2>
    ${events.map(e => `<div class="history-entry ${e.error ? 'failed' : !e.sent ? 'pending' : ''}"><strong>${escapeHTML(e.type)}</strong> · ${escapeHTML(shortTime(e.time))}<br><small>${e.cancelled ? 'Отменено до отправки' : e.sent ? 'Принято 1С' : e.error ? `Не принято: ${escapeHTML(e.error)}` : 'Ожидает отправки'}</small></div>`).join('') || '<div class="empty">Событий пока нет.</div>'}</section>`;
}
function renderSettings() {
  const a = activeAssignment();
  main.innerHTML = `<section class="page"><h2 class="screen-title">Настройки</h2>
    <div class="fields">${field('Водитель',a?.driver?.name || state.driverId)}${field('Контур',prefix === '/prod' ? 'Рабочая база' : 'Тестовая база test3')}</div>
    <h2 class="section-label">Уведомления</h2><p class="subtitle">Добавьте сайт на экран «Домой», затем разрешите уведомления на iPhone.</p><button class="button secondary wide" data-action="push-enable">Включить уведомления</button>
    <div class="divider"></div><button class="button secondary wide" data-action="refresh">Обновить данные</button><div class="divider"></div><button class="button danger wide" data-action="logout">Отвязать этот iPhone</button></section>`;
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
    comment:extra.comment || '', reason:extra.reason || undefined, reason_code:extra.reason_code || undefined,
    geo:moment ? moment.geo : await geoAtTap() };
  const records = photos.map(photo => ({id:crypto.randomUUID(),driver_id:state.driverId,assignment_id:a.id,trip_id:tripId,event_id:id,kind:photo.kind,blob:photo.blob,sent:false}));
  await putAction(state.db,event,records);
  await reloadLocal(); render(); flash('Отметка записана');
  await flush();
}
async function flush() {
  if (state.syncing) { state.flushAgain = true; return; }
  if (!state.db || !navigator.onLine) return;
  state.syncing = true;
  let expired = false;
  try {
    try {
      const events = state.events.filter(e => !e.sent && !e.cancelled && !e.error);
      if (events.length) {
        const response = await request('/events', { method:'POST', body:JSON.stringify({ events:events.map(({sent,error,assignment_version,cancelled,...e}) => e) }) });
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
    if (!expired) await reloadLocal();
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
function closeModal() { modal.hidden = true; modal.innerHTML = ''; }
function selectedAssignment(id) { return state.assignments.find(a => a.id === id); }
function selectedTrip(a,id) { return a?.trips.find(t => t.id === id); }

async function photoFlow(prompts) {
  const photos = [];
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'}},audio:false});
    for (const prompt of prompts) {
      const blob = await new Promise((resolve,reject) => {
        showModal(`<h2>${escapeHTML(prompt.label)}</h2><p class="subtitle">${escapeHTML(prompt.hint)}</p><video class="camera-video" autoplay playsinline muted></video><button class="button wide large" id="take-photo">Снять фото</button><button class="button ghost wide" id="cancel-photo">Отменить</button>`);
        const video = modal.querySelector('video'); video.srcObject = stream; video.play().catch(() => {});
        modal.querySelector('#cancel-photo').onclick = () => reject(new Error('Съёмка отменена'));
        modal.querySelector('#take-photo').onclick = async () => {
          try {
            const canvas = document.createElement('canvas');
            const scale = Math.min(1, 2000 / Math.max(video.videoWidth,video.videoHeight));
            canvas.width = Math.round(video.videoWidth * scale); canvas.height = Math.round(video.videoHeight * scale);
            if (!canvas.width || !canvas.height) throw new Error('Камера ещё запускается');
            canvas.getContext('2d').drawImage(video,0,0,canvas.width,canvas.height);
            let quality = .9, output;
            do { output = await new Promise(done => canvas.toBlob(done,'image/jpeg',quality)); quality -= .1; }
            while (output?.size > 1_500_000 && quality >= .4);
            if (!output) throw new Error('Не удалось сохранить фото');
            resolve(output);
          } catch (error) { flash(errorText(error)); }
        };
      });
      photos.push({kind:prompt.kind,blob});
    }
    closeModal();
    return photos;
  } finally { stream?.getTracks().forEach(track => track.stop()); closeModal(); }
}
const photoPrompts = {
  ЗагрузилсяВПуть:[{kind:'truck_rear',label:'Грузовик сзади',hint:'Кузов и госномер целиком в кадре'},{kind:'truck_side',label:'Грузовик сбоку',hint:'Машина целиком вместе с грузом'}],
  Разгрузился:[{kind:'truck_rear',label:'Грузовик сзади',hint:'Кузов и госномер целиком в кадре'},{kind:'truck_side',label:'Грузовик сбоку',hint:'Машина целиком в кадре'},{kind:'document',label:'Подписанный документ',hint:'Весь лист, подпись и печать должны читаться'}]
};
async function doStep(a,trip,type) {
  if (state.busy) return; state.busy = true;
  try {
    const moment = await eventMoment();
    let photos = [];
    if (photoPrompts[type]) photos = await photoFlow(photoPrompts[type]);
    let comment = '';
    if (type === 'Разгрузился') {
      const answer = await askComment('Комментарий к разгрузке (необязательно)');
      if (answer === null) return;
      comment = answer;
    }
    await enqueue(a,type,trip.id,{comment},photos,moment);
  } catch (error) { if (errorText(error) !== 'Съёмка отменена') flash(errorText(error)); }
  finally { state.busy = false; }
}
function askComment(title) {
  return new Promise(resolve => {
    showModal(`<h2>${escapeHTML(title)}</h2><label class="field"><span>Комментарий</span><textarea id="comment" maxlength="500"></textarea></label><button class="button wide" id="comment-ok">Продолжить</button><button class="button ghost wide" id="comment-cancel">Отмена</button>`);
    modal.querySelector('#comment-ok').onclick = () => { const text=modal.querySelector('#comment').value.trim();closeModal();resolve(text); };
    modal.querySelector('#comment-cancel').onclick = () => {closeModal();resolve(null);};
  });
}
function failureFlow(a,trip) {
  const progress = tripProgress(a,trip);
  const client = progress.done.has('ПрибылНаРазгрузку') && !progress.done.has('Разгрузился') && !trip.return_to_depot;
  const options = client ? [['КлиентНеГотов','Клиент не готов принять (время не согласовано)'],['Качество','Претензия к качеству'],['Прочее','Другая причина']] :
    [['','Поломка машины'],['','Нет подъезда'],['','Груз не помещается'],['','Другое']];
  showModal(`<h2>${client ? 'Клиент не принял груз' : 'Не могу выполнить рейс'}</h2><p class="subtitle">Рейс останется невыполненным до решения диспетчера.</p>
    <form id="fail-form"><label class="field"><span>Причина</span><select id="fail-choice" required><option value="">Выберите причину</option>${options.map(([code,text],i) => `<option value="${i}">${escapeHTML(text)}</option>`).join('')}</select></label>
    <label class="field"><span>Причина для 1С</span><input id="fail-reason" maxlength="200" required></label><label class="field"><span>Комментарий диспетчеру</span><textarea id="fail-comment" maxlength="500"></textarea></label>
    <div id="fail-photo-note"></div><button class="button wide large">Подтвердить</button><button type="button" class="button ghost wide" id="fail-cancel">Отмена</button></form>`);
  const form = modal.querySelector('#fail-form');
  form.querySelector('#fail-choice').onchange = () => {
    const value=form.querySelector('#fail-choice').value;
    const selected=value === '' ? null : options[Number(value)];
    form.querySelector('#fail-reason').value = selected ? selected[1] === 'Другое' || selected[0] === 'Прочее' ? '' : client ? `Клиент не принимает: ${selected[1]}` : selected[1] : '';
    form.querySelector('#fail-photo-note').textContent = selected?.[0] === 'Качество' ? 'После подтверждения потребуется фото груза.' : '';
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
    state.busy=true;
    try {
      const moment = await eventMoment();
      const code=choice[0] || undefined; closeModal();
      const photos=code==='Качество' ? await photoFlow([{kind:'cargo',label:'Сфотографируйте груз',hint:'Покажите причину отказа клиента'}]) : [];
      await enqueue(a,'Срыв',trip.id,{reason,comment,reason_code:code},photos,moment);
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
async function logout() {
  try {
    const subscription = await navigator.serviceWorker?.ready.then(reg => reg.pushManager?.getSubscription());
    if (subscription) {
      await request('/push/subscriptions',{method:'DELETE',body:JSON.stringify({endpoint:subscription.endpoint})});
      await subscription.unsubscribe();
    }
  } catch { /* Отвязка сессии всё равно должна завершиться. */ }
  await request('/logout',{method:'POST',body:'{}'});
  localStorage.removeItem(`arm_driver_last_${prefix || 'test3'}`);
  state.db?.close(); state.driverId=null;state.db=null;state.assignments=[];state.events=[];state.photos=[];renderPair();
}

document.addEventListener('click', async event => {
  const target = event.target.closest('[data-action],[data-screen]');
  if (!target) return;
  if (target.dataset.screen) { state.screen=target.dataset.screen; render(); window.scrollTo(0,0); return; }
  const action=target.dataset.action, a=selectedAssignment(target.dataset.id || target.dataset.assignment), trip=selectedTrip(a,target.dataset.trip);
  try {
    if (action==='refresh') await refreshAssignments();
    if (action==='retry') await retryPending();
    if (action==='open-assignment' && a) {state.selectedId=a.id;state.assignmentDetail=true;render();window.scrollTo(0,0);}
    if (action==='back-assignments') {state.assignmentDetail=false;render();window.scrollTo(0,0);}
    if (action==='open-new-assignment' && a) {state.newAssignmentNoticeId=null;closeModal();state.selectedId=a.id;state.assignmentDetail=true;state.screen='assignments';render();window.scrollTo(0,0);}
    if (action==='dismiss-new-assignment') {state.newAssignmentNoticeId=null;closeModal();}
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
    if (action==='logout') {
      if (pendingItems().length) { flash('Сначала отправьте очередь. Неотправленные данные остаются на этом iPhone.'); return; }
      await logout();
    }
    if (action==='push-enable') await enablePush();
  } catch (error) { flash(errorText(error)); }
});

start();
