import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/db.js', import.meta.url), 'utf8')
  .replace(/^export /gm, '');
const context = vm.createContext({setTimeout, clearTimeout});
vm.runInContext(source + '\nglobalThis.readAll = all;', context);

test('очередь событий читается курсором без getAll и не меняется', async () => {
  const events = [
    {id:'first',type:'Ознакомление',sent:true},
    {id:'second',type:'ПрибылНаПогрузку',sent:false},
  ];
  let index = 0;
  let request;
  let transaction;
  const db = {transaction(name, mode) {
    assert.equal(name,'events');
    assert.equal(mode,'readonly');
    transaction = {objectStore() { return {
      getAll() { throw new Error('Safari getAll вернул пустые записи'); },
      openCursor() {
        request = {};
        queueMicrotask(advance);
        return request;
      },
    }; }};
    return transaction;
  }};
  function advance() {
    if (index < events.length) {
      request.result = {value:events[index++],continue:() => queueMicrotask(advance)};
      request.onsuccess();
    } else {
      request.result = null;
      request.onsuccess();
      transaction.oncomplete();
    }
  }
  const result = await context.readAll(db,'events');
  assert.deepEqual(JSON.parse(JSON.stringify(result)),events);
  assert.equal(events[1].sent,false);
});
