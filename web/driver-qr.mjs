const guidPattern = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g;
const hex = /[0-9a-fA-F]/;

// QR из 1С: GUID водителя и ФИО на отдельных строках. Совпадает с Android.
export function parseDriverQR(raw) {
  const value = String(raw || '');
  const ids = [...value.matchAll(guidPattern)]
    .filter(match => !hex.test(value[match.index - 1] || '') && !hex.test(value[match.index + match[0].length] || ''))
    .map(match => match[0].toLowerCase());
  const unique = [...new Set(ids)];
  if (unique.length !== 1) return null;
  const others = value.split(/[\n\r;]/).map(part => part.trim())
    .filter(part => part && !part.includes(ids[0]) && !part.toLowerCase().includes(unique[0]));
  const name = others.length === 1 && /[\p{L}]/u.test(others[0]) ? others[0] : null;
  return { driverId: unique[0], name };
}

export function validDriverGUID(value) {
  return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value.trim());
}
