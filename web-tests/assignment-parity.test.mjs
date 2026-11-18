import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/app.js', import.meta.url), 'utf8')
  .replace(/^import .*;\n/gm, '')
  .replace(/\nstart\(\);\s*$/, '');
const context = vm.createContext({
  location:{pathname:'/web/'},
  document:{querySelector:() => ({textContent:'',hidden:true}),addEventListener:() => {}},
  navigator:{onLine:true},
  localStorage:{getItem:() => null},
  window:{},
  setTimeout,clearTimeout,Date,Intl,console,
});
vm.runInContext(source + '\nglobalThis.parity = {state,main,mergeAssignment,keysOnlyVersionBump,activeAssignment,tripProgress,acknowledged,phase,renderTrips,renderSettings,renderService};',context);
const {state,main,mergeAssignment,keysOnlyVersionBump,activeAssignment,tripProgress,acknowledged,phase,renderTrips,renderSettings,renderService} = context.parity;
const id = n => `${String(n).padStart(8,'0')}-1111-4111-8111-111111111111`;
const trip = (n, overrides={}) => ({id:id(n),order:n,status:'Назначена',load_point:{name:'Завод'},unload_point:{name:'Клиент'},stages:null,next_stage:'ПрибылНаПогрузку',...overrides});
const assignment = (n, overrides={}) => ({id:id(n),version:1,number:String(n),departure_day:'2026-11-18',status:'Назначена',
  driver:{id:id(99),name:'Водитель'},vehicle:{name:'Машина'},keys_issued:false,keys_accepted:false,shift_closed:false,shift_started:false,
  trips:[trip(n+100)],...overrides});

test('замена разнарядки выбирает новую, даже пока старая смена открыта', () => {
  const old = assignment(36,{status:'Заменена',shift_started:true,replacement:{id:id(37),number:'37'}});
  const next = assignment(37,{shift_started:true,basis:{id:id(36),number:'36'}});
  state.assignments=[old,next];state.events=[];
  assert.equal(activeAssignment().id,next.id);
  assert.equal(phase(old),'Заменена');
});

test('рейс, пропавший из нового пакета, остаётся снятым', () => {
  const old=assignment(36,{trips:[trip(1),trip(2)]});
  const fresh=assignment(36,{version:2,trips:[trip(2)]});
  const merged=mergeAssignment(old,fresh);
  assert.equal(merged.trips.length,2);
  assert.equal(merged.trips.find(item=>item.id===id(1)).status,'Отменена');
  assert.equal(merged.ack_required_version,2);
});

test('выдача ключей требует автоматического ознакомления новой версии', () => {
  const old=mergeAssignment(null,assignment(36));
  state.assignments=[old];state.events=[{id:id(500),assignment_id:old.id,type:'Ознакомление',assignment_version:1,sent:true,cancelled:false,error:''}];
  const fresh=assignment(36,{version:2,keys_issued:true});
  assert.equal(keysOnlyVersionBump(old,fresh),true);
  const merged=mergeAssignment(old,fresh);
  assert.equal(merged.ack_required_version,1);
  state.assignments=[merged];
  assert.equal(acknowledged(merged),true);
  const changed=assignment(36,{version:3,keys_issued:true,trips:[trip(136,{unload_point:{name:'Новый адрес'}})]});
  assert.equal(keysOnlyVersionBump(merged,changed),false);
  assert.equal(mergeAssignment(merged,changed).ack_required_version,3);
});

test('перенаправленный после срыва рейс сохраняет погрузку и открывает новый адрес', () => {
  const a=assignment(36,{version:2,trips:[trip(136,{next_stage:'ПрибылНаРазгрузку',stages:{arrived_load:'2026-11-18T08:00:00+03:00',loaded:'2026-11-18T08:10:00+03:00'}})]});
  state.assignments=[a];state.events=[
    {assignment_id:a.id,trip_id:id(136),type:'ПрибылНаРазгрузку',time:'2026-11-18T09:00:00+03:00',assignment_version:1,sent:true,cancelled:false,error:''},
    {assignment_id:a.id,trip_id:id(136),type:'Срыв',time:'2026-11-18T09:05:00+03:00',assignment_version:1,sent:true,cancelled:false,error:''},
  ];
  const progress=tripProgress(a,a.trips[0]);
  assert.equal(progress.returned,true);
  assert.equal(progress.next,'ПрибылНаРазгрузку');
  assert.equal(progress.done.has('ЗагрузилсяВПуть'),true);
  assert.equal(progress.done.has('Срыв'),false);
});

test('вкладка рейсов берёт текущую разнарядку и раскрывает активный рейс', () => {
  const old=assignment(36,{status:'Заменена',shift_started:true,replacement:{id:id(37)}});
  const next=assignment(37,{shift_started:true,keys_issued:true,basis:{id:id(36)}});
  state.assignments=[old,next];state.events=[];state.expandedTripId=null;state.lastActiveTripId=null;
  renderTrips();
  assert.match(main.innerHTML,/Разнарядка № 37/);
  assert.doesNotMatch(main.innerHTML,/Разнарядка № 36/);
  assert.match(main.innerHTML,/class="trip-body"(?! hidden)/);
});

test('служебная привязка совпадает с Android, выхода у водителя нет', () => {
  state.driverId=id(99);state.assignments=[];state.events=[];state.dispatcherPhone='';
  renderSettings();
  assert.match(main.innerHTML,/Служебный вход/);
  assert.doesNotMatch(main.innerHTML,/Выйти|Сменить водителя/);
  renderService();
  assert.match(main.innerHTML,/Сканировать QR-код/);
  assert.match(main.innerHTML,/Привязать по введённому/);
});
