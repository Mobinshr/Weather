const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '..', 'weather-ios.html'), 'utf8');
assert.equal((html.match(/#sug\\{position:absolute/g) || []).length, 1, 'the dropdown must have one authoritative floating layout rule');
assert.doesNotMatch(html, /#sug\\{position:relative/, 'the obsolete in-flow dropdown layout must be absent');
assert.match(html, /#sug\[hidden\]\{display:none!important\}/);
assert.match(html, /#sug li:hover/);

const cacheStart = html.indexOf('function cacheKeyFor(');
const cacheEnd = html.indexOf('/* ============ Notification permission & fire ============ */', cacheStart);
assert.ok(cacheStart >= 0 && cacheEnd > cacheStart);
const cacheSource = html.slice(cacheStart, cacheEnd);
const values = new Map();
const storage = {
  get length() { return values.size; },
  key(index) { return [...values.keys()][index] ?? null; },
  getItem(key) { return values.has(key) ? values.get(key) : null; },
  setItem(key, value) { values.set(key, String(value)); },
  removeItem(key) { values.delete(key); }
};
const cacheContext = { localStorage:storage, Date, Number, Math, JSON, Object, Array, Infinity };
vm.createContext(cacheContext);
vm.runInContext(
  'const CACHE_PREFIX = "ios_weather_"; const MAX_CACHE_AGE = 24*60*60*1000; const MAX_CACHE_ENTRIES = 12;\n' +
  cacheSource +
  '\nglobalThis.cacheApi = { cacheKeyFor, isUsableWeatherData, saveCache, loadCache, pruneWeatherCache };',
  cacheContext
);
const cache = cacheContext.cacheApi;
const valid = {
  current: { temperature_2m:12, apparent_temperature:10, relative_humidity_2m:65, weather_code:2, wind_speed_10m:8 },
  hourly: { time:['2026-11-25T10:00'], temperature_2m:[12], weather_code:[2] },
  daily: { time:['2026-11-25'], temperature_2m_max:[15], temperature_2m_min:[7], weather_code:[2] }
};
assert.equal(cache.isUsableWeatherData(valid), true);
assert.equal(cache.isUsableWeatherData({ current:{}, hourly:{}, daily:{} }), false);
const now = Date.now();
storage.setItem('ios_weather_last_city', JSON.stringify({ name:'پیرانشهر', lat:36.7, lon:45.1 }));
storage.setItem('ios_weather_notif_enabled', '1');
assert.equal(cache.saveCache(36,45,valid,20,now), true);
assert.ok(storage.getItem('ios_weather_last_city'), 'cache pruning must preserve the saved city');
assert.equal(storage.getItem('ios_weather_notif_enabled'), '1', 'cache pruning must preserve notification preferences');
assert.equal(cache.loadCache(36,45).aqi, 20);
assert.equal(cache.loadCache(36,45).at, now);
const expiredKey = cache.cacheKeyFor(35,45);
storage.setItem(expiredKey, JSON.stringify({ data:valid, aqi:null, at:now - 25*60*60*1000 }));
assert.equal(cache.loadCache(35,45), null);
assert.equal(storage.getItem(expiredKey), null);
for (let i=0;i<16;i++) cache.saveCache(10+i,40,valid,null,now+i);
const weatherKeys = [...values.keys()].filter(key => key.startsWith('ios_weather_'));
assert.ok(weatherKeys.length <= 12, 'cache should retain no more than 12 cities');

const loadStart = html.indexOf('async function load(lat, lon, name, region, options = {})');
const loadEnd = html.indexOf('/* ============ Bilingual city search', loadStart);
assert.ok(loadStart >= 0 && loadEnd > loadStart);
const loadSource = html.slice(loadStart, loadEnd);
assert.match(loadSource, /if \(loadController\) loadController\.abort\(\)/);
assert.match(loadSource, /requestId !== loadSequence/);
assert.match(loadSource, /controller\.signal\.aborted/);

let rendered = [];
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes,no) => { resolve=yes; reject=no; });
  return { promise, resolve, reject };
};
const mockGet = (url, options={}) => {
  const request = deferred();
  const signal = options.signal;
  if (signal) {
    if (signal.aborted) request.reject(Object.assign(new Error('aborted'),{name:'AbortError'}));
    else signal.addEventListener('abort', () => request.reject(Object.assign(new Error('aborted'),{name:'AbortError'})), {once:true});
  }
  if (url.startsWith('aqi:')) request.resolve({ current:{ european_aqi:Number(url.slice(4)) } });
  else if (url === 'weather:2') request.resolve(valid);
  return request.promise;
};
const button = { disabled:false, classList:{ add(){}, remove(){} } };
const sandbox = {
  AbortController, Date, Number, Math, JSON, Promise,
  navigator:{ onLine:true }, localStorage:{ setItem(){} },
  LAST_CITY_KEY:'last', CACHE_FRESH_MS:15*60*1000,
  loadingInProgress:false, loadSequence:0, loadController:null,
  current:{}, lastWeather:null, lastAqi:null, lastSavedAt:null, offlineMode:false,
  $:id => id==='refresh' ? button : {},
  cacheKeyFor:(lat,lon)=>'ios_weather_' + lat + '_' + lon,
  loadCache:()=>null, isUsableWeatherData:()=>true, saveCache(){},
  API:lat=>'weather:' + lat, AQI_API:lat=>'aqi:' + lat,
  get:mockGet, render:(data,aqi,name)=>rendered.push(name),
  showMessage(){}, showToast(){}, updateStatusDot(){}, startCountdown(){},
  updateCountdown(){}, console
};
vm.createContext(sandbox);
vm.runInContext(loadSource + '\nglobalThis.runLoad = load;',sandbox);
const first = sandbox.runLoad(1,1,'City A','Region A');
const second = sandbox.runLoad(2,2,'City B','Region B');
Promise.all([first,second]).then(() => {
  assert.equal(sandbox.current.name,'City B');
  assert.ok(rendered.includes('City B'));
  assert.ok(!rendered.includes('City A'));
  assert.equal(sandbox.loadingInProgress,false);
  console.log('Weather UI runtime tests passed: dropdown, cache, and concurrent city changes.');
}).catch(error => {
  console.error(error);
  process.exitCode=1;
});
