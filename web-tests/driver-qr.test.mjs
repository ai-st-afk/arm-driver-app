import test from 'node:test';
import assert from 'node:assert/strict';
import { parseDriverQR, validDriverGUID } from '../web/driver-qr.mjs';

const id = '3c9d1a55-77e2-4f0b-8a6c-1d2e3f405162';

test('QR водителя из 1С читается как в Android', () => {
  assert.deepEqual(parseDriverQR(`${id}\nКостенко Павел Сергеевич`), { driverId:id, name:'Костенко Павел Сергеевич' });
  assert.deepEqual(parseDriverQR(`Костенко Павел;${id.toUpperCase()}`), { driverId:id, name:'Костенко Павел' });
  assert.deepEqual(parseDriverQR(id), { driverId:id, name:null });
});

test('чужой или неоднозначный QR отклоняется', () => {
  assert.equal(parseDriverQR('https://example.com/promo'), null);
  assert.equal(parseDriverQR(`${id}\ne8d9c721-fc51-4dbc-933c-73b8544d67b7`), null);
  assert.equal(parseDriverQR(`a${id}`), null);
  assert.equal(validDriverGUID('ошибка'), false);
  assert.equal(validDriverGUID(id), true);
});
