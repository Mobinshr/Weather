const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '..', 'weather-ios.html'), 'utf8');
const start = html.indexOf('let searchTimer = null;');
const end = html.indexOf('async function useQandilCoordinates', start);
assert.ok(start >= 0 && end > start, 'city search block must exist in weather-ios.html');
const searchBlock = html.slice(start, end);

function makeElement() {
  return {
    hidden: true,
    value: '',
    innerHTML: '',
    attrs: {},
    listeners: {},
    addEventListener(type, handler) { this.listeners[type] = handler; },
    setAttribute(name, value) { this.attrs[name] = value; },
    querySelectorAll() { return this.items || []; }
  };
}

const elements = { q: makeElement(), sug: makeElement(), searchArea: makeElement() };
elements.searchArea.contains = () => true;
const calls = [];
const failedLanguages = new Set();
const fixtureResults = {
  en: [
    { id: 100, name: 'Paris', latitude: 48.8566, longitude: 2.3522, country: 'France', admin1: 'Ile-de-France', population: 2161000 },
    { id: 101, name: 'Paris', latitude: 33.6609, longitude: -95.5555, country: 'United States', admin1: 'Texas', population: 25000 }
  ],
  fa: [
    { id: 100, name: 'پاریس', latitude: 48.8566, longitude: 2.3522, country: 'فرانسه', admin1: 'ایل-دو-فرانس', population: 2161000 },
    { id: 102, name: 'پاریس کوچک', latitude: 35.1, longitude: 51.2, country: 'ایران', admin1: 'تهران', population: 1200 }
  ]
};
let loaded = null;

const sandbox = {
  AbortController,
  setTimeout,
  clearTimeout,
  console,
  $: id => elements[id],
  fa: value => String(value),
  load: (...args) => { loaded = args; },
  document: { addEventListener() {} },
  fetch: async url => {
    const parsed = new URL(url);
    const language = parsed.searchParams.get('language');
    calls.push(language);
    if (failedLanguages.has(language)) throw new Error('simulated ' + language + ' index failure');
    return {
      ok: true,
      json: async () => ({ results: fixtureResults[language] || [] })
    };
  }
};

const apiPrelude =
  "const GEO_API = (v, lang) => 'https://geocoding-api.open-meteo.com/v1/search?name=' + encodeURIComponent(v) + '&count=100&language=' + lang + '&format=json';";
vm.createContext(sandbox);
vm.runInContext(apiPrelude + '\n' + searchBlock +
  '\nglobalThis.citySearchTestApi = { searchCities, renderCityResults, selectSearchResult };', sandbox);

(async () => {
  const results = await sandbox.citySearchTestApi.searchCities('Paris', new AbortController().signal);
  const plain = JSON.parse(JSON.stringify(results));
  assert.deepEqual(new Set(calls), new Set(['en', 'fa']), 'both language indexes must be queried');
  assert.equal(plain.length, 3, 'different cities from both languages must be merged');
  assert.ok(plain[0].alternateNames.includes('پاریس'), 'translated name must be retained as an alternate label');

  sandbox.citySearchTestApi.renderCityResults(results, 'Paris');
  assert.equal(elements.sug.hidden, false, 'rendering results must open the dropdown');
  assert.match(elements.sug.innerHTML, /Paris/);
  assert.match(elements.sug.innerHTML, /پاریس/);
  assert.equal(elements.q.attrs['aria-expanded'], 'true');

  sandbox.citySearchTestApi.selectSearchResult({
    dataset: { a: '48.8566', o: '2.3522', n: 'Paris', r: 'Ile-de-France, France' }
  });
  assert.deepEqual(loaded, [48.8566, 2.3522, 'Paris', 'Ile-de-France, France']);
  assert.equal(elements.q.value, '');

  failedLanguages.clear();
  failedLanguages.add('fa');
  const partial = JSON.parse(JSON.stringify(
    await sandbox.citySearchTestApi.searchCities('تهران', new AbortController().signal)
  ));
  assert.equal(partial.length, 2, 'one language failure must not hide successful results from the other');

  failedLanguages.add('en');
  await assert.rejects(
    sandbox.citySearchTestApi.searchCities('شهر تازه', new AbortController().signal),
    /simulated/
  );

  console.log('City dropdown tests passed: bilingual merge, deduplication, rendering, selection, partial failure.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
