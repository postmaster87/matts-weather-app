'use strict';

/* ============================================================
   Weather — hourly / 5-day / radar. No ads, no trackers.
   Data: Open-Meteo (forecast + geocoding), NWS (alerts),
         RainViewer (radar tiles), CARTO (basemap).
   No API keys anywhere. All requests are anonymous GETs.
   ============================================================ */

const DEFAULT_PLACE = { name: 'Ames', region: 'Iowa', country: 'US', lat: 42.0308, lon: -93.6319 };
const LS_PLACE = 'wx.place';
const LS_RECENTS = 'wx.recents';
const LS_CACHE = 'wx.cache';
const STALE_MS = 10 * 60 * 1000;

const $ = (s) => document.querySelector(s);
const pad2 = (n) => (n < 10 ? '0' + n : '' + n);
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

let place = null;
let wxData = null;
let selectedDay = -1;      // -1 = rolling 48h view
let lastFetch = 0;

/* ------------------------------------------------------------
   Icons — 24x24, filled, readable at 24px on a phone in daylight
   ------------------------------------------------------------ */
const C_SUN = '#ffc44d', C_MOON = '#cfd9e6', C_CLOUD = '#b6c4d4',
      C_CLOUD_D = '#8d9db1', C_RAIN = '#5fa8ff', C_SNOW = '#dcebf7', C_BOLT = '#ffd24d';

function rays(cx, cy, r0, r1, w) {
  let s = '';
  for (let a = 0; a < 360; a += 45) {
    const t = a * Math.PI / 180, c = Math.cos(t), n = Math.sin(t);
    s += `<path d="M${(cx + c * r0).toFixed(1)} ${(cy + n * r0).toFixed(1)}L${(cx + c * r1).toFixed(1)} ${(cy + n * r1).toFixed(1)}" stroke="${C_SUN}" stroke-width="${w}" stroke-linecap="round"/>`;
  }
  return s;
}
const SUN_BIG = `<circle cx="12" cy="12" r="4.6" fill="${C_SUN}"/>` + rays(12, 12, 7.2, 9.8, 2);
const SUN_SM = `<circle cx="8.4" cy="7.6" r="3.1" fill="${C_SUN}"/>` + rays(8.4, 7.6, 4.6, 6.4, 1.7);
const MOON_BIG = `<path d="M20.2 14.8A8.7 8.7 0 0 1 9.2 3.8a8.7 8.7 0 1 0 11 11z" fill="${C_MOON}"/>`;
const MOON_SM = `<path d="M12.4 9.4A5.6 5.6 0 0 1 5.3 2.3a5.6 5.6 0 1 0 7.1 7.1z" fill="${C_MOON}"/>`;

function cloud(fill, dx, dy, k) {
  // Scale about (12,11) then translate — baked into one matrix so we do not
  // depend on the SVG transform-origin attribute being honored.
  const s = k || 1, x = (12 * (1 - s) + (dx || 0)).toFixed(2), y = (11 * (1 - s) + (dy || 0)).toFixed(2);
  return `<g transform="translate(${x} ${y}) scale(${s})">` +
    `<circle cx="8.8" cy="8.8" r="3.4" fill="${fill}"/>` +
    `<circle cx="14.4" cy="8.0" r="4.2" fill="${fill}"/>` +
    `<rect x="5.4" y="9.9" width="13.2" height="4.4" rx="2.2" fill="${fill}"/></g>`;
}
function drops(n, len, color) {
  const xs = n === 2 ? [9.5, 14.5] : [8, 12, 16];
  return xs.map((x, i) =>
    `<path d="M${x} ${15.8 + (i % 2) * 0.8}v${len + (i === 1 ? 1.2 : 0)}" stroke="${color}" stroke-width="2" stroke-linecap="round"/>`
  ).join('');
}
function flakes() {
  return [8, 12, 16].map((x, i) =>
    `<circle cx="${x}" cy="${17.6 + (i % 2) * 2.4}" r="1.35" fill="${C_SNOW}"/>`
  ).join('');
}
const BOLT = `<path d="M13.6 15.2 9.2 21.4h2.9L10.9 24.4 15.6 18h-3z" fill="${C_BOLT}"/>`;
const FOGLINES = `<g stroke="${C_CLOUD}" stroke-width="2" stroke-linecap="round">` +
  `<path d="M6.5 17.6h11"/><path d="M8.5 21h8"/></g>`;

const ICONS = {
  clear: SUN_BIG,
  'clear-n': MOON_BIG,
  mclear: SUN_SM + cloud(C_CLOUD, 3.2, 3.4, 0.82),
  'mclear-n': MOON_SM + cloud(C_CLOUD, 3.2, 3.4, 0.82),
  partly: SUN_SM + cloud(C_CLOUD, 1.6, 2.6, 0.94),
  'partly-n': MOON_SM + cloud(C_CLOUD, 1.6, 2.6, 0.94),
  cloudy: cloud(C_CLOUD, 0, 1.4, 1),
  overcast: cloud(C_CLOUD_D, -1.8, 0.4, 0.86) + cloud(C_CLOUD, 1.6, 3.2, 0.94),
  fog: cloud(C_CLOUD_D, 0, -0.6, 0.92) + FOGLINES,
  drizzle: cloud(C_CLOUD, 0, 0, 1) + drops(2, 2.6, C_RAIN),
  rain: cloud(C_CLOUD, 0, 0, 1) + drops(3, 3.4, C_RAIN),
  heavy: cloud(C_CLOUD_D, 0, 0, 1) + drops(3, 5.6, C_RAIN),
  sleet: cloud(C_CLOUD, 0, 0, 1) + `<path d="M9 15.8v3.4" stroke="${C_RAIN}" stroke-width="2" stroke-linecap="round"/><circle cx="14.6" cy="18.4" r="1.35" fill="${C_SNOW}"/>`,
  snow: cloud(C_CLOUD, 0, 0, 1) + flakes(),
  storm: cloud(C_CLOUD_D, 0, 0, 1) + BOLT
};

function wxInfo(code, isDay) {
  const d = isDay !== 0;
  if (code === 0) return [d ? 'clear' : 'clear-n', 'Clear'];
  if (code === 1) return [d ? 'mclear' : 'mclear-n', 'Mostly clear'];
  if (code === 2) return [d ? 'partly' : 'partly-n', 'Partly cloudy'];
  if (code === 3) return ['overcast', 'Overcast'];
  if (code === 45 || code === 48) return ['fog', 'Fog'];
  if (code >= 51 && code <= 55) return ['drizzle', 'Drizzle'];
  if (code === 56 || code === 57) return ['sleet', 'Freezing drizzle'];
  if (code === 61 || code === 80) return ['rain', 'Light rain'];
  if (code === 63 || code === 81) return ['rain', 'Rain'];
  if (code === 65 || code === 82) return ['heavy', 'Heavy rain'];
  if (code === 66 || code === 67) return ['sleet', 'Freezing rain'];
  if (code === 71 || code === 85) return ['snow', 'Light snow'];
  if (code === 73) return ['snow', 'Snow'];
  if (code === 75 || code === 86) return ['snow', 'Heavy snow'];
  if (code === 77) return ['snow', 'Snow grains'];
  if (code === 95) return ['storm', 'Thunderstorms'];
  if (code === 96 || code === 99) return ['storm', 'Storms, hail'];
  return ['cloudy', 'Cloudy'];
}
function icon(code, isDay, cls) {
  const key = wxInfo(code, isDay)[0];
  return `<svg class="${cls || ''}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[key] || ICONS.cloudy}</svg>`;
}

/* ------------------------------------------------------------
   Time helpers.
   Open-Meteo returns LOCAL-TO-THE-FORECAST times as "YYYY-MM-DDTHH:MM"
   with timezone=auto. Parse the string, never new Date(str) — that would
   reinterpret it in the phone's timezone and be wrong for other places.
   ------------------------------------------------------------ */
const hourOf = (iso) => parseInt(iso.slice(11, 13), 10);
const dateOf = (iso) => iso.slice(0, 10);
function dowOf(iso) {
  const p = iso.slice(0, 10).split('-');
  return DOW[new Date(+p[0], +p[1] - 1, +p[2]).getDay()];
}
function h12(iso) {
  const h = hourOf(iso);
  return (h % 12 === 0 ? 12 : h % 12) + (h < 12 ? 'a' : 'p');
}
function hm12(iso) {
  const h = hourOf(iso), m = iso.slice(14, 16);
  return (h % 12 === 0 ? 12 : h % 12) + ':' + m + (h < 12 ? 'am' : 'pm');
}
function clockLocal(ms) {
  const d = new Date(ms), h = d.getHours();
  return (h % 12 === 0 ? 12 : h % 12) + ':' + pad2(d.getMinutes()) + (h < 12 ? 'am' : 'pm');
}
const CARD = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];
const cardinal = (deg) => CARD[Math.round(((deg % 360) + 360) % 360 / 22.5) % 16];

function toast(msg, ms) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { t.hidden = true; }, ms || 2600);
}

/* ------------------------------------------------------------
   Data
   ------------------------------------------------------------ */
function forecastURL(lat, lon) {
  const p = new URLSearchParams({
    latitude: lat.toFixed(4),
    longitude: lon.toFixed(4),
    current: 'temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m',
    hourly: 'temperature_2m,precipitation_probability,weather_code,uv_index,is_day',
    daily: 'weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max',
    temperature_unit: 'fahrenheit',
    wind_speed_unit: 'mph',
    precipitation_unit: 'inch',
    timezone: 'auto',
    forecast_days: '7'
  });
  return 'https://api.open-meteo.com/v1/forecast?' + p;
}

async function getJSON(url, ms) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), ms || 12000);
  try {
    const r = await fetch(url, { signal: ac.signal, cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return await r.json();
  } finally { clearTimeout(t); }
}

async function loadWeather(showSpin, attempt) {
  const tries = attempt || 1;
  if (showSpin) $('#refreshBtn').classList.add('spin');
  try {
    const data = await getJSON(forecastURL(place.lat, place.lon));
    wxData = data;
    lastFetch = Date.now();
    try {
      localStorage.setItem(LS_CACHE, JSON.stringify({ t: lastFetch, place, data }));
    } catch (e) { /* private mode / quota — not fatal */ }
    renderAll(false);
    loadAlerts();
    if (radar.map) radar.map.setView([place.lat, place.lon], radar.map.getZoom());
  } catch (e) {
    // One retry — a single dropped request on cell data should not leave a
    // dead screen.
    if (tries < 2) {
      setTimeout(() => loadWeather(showSpin, tries + 1), 1500);
      return;
    }
    if (wxData) toast('Refresh failed — showing last data');
    else $('#current').innerHTML = '<div class="err">Could not reach the forecast.<br>Check signal and hit refresh.</div>';
  } finally {
    $('#refreshBtn').classList.remove('spin');
  }
}

async function loadAlerts() {
  const box = $('#alerts');
  box.hidden = true;
  box.innerHTML = '';
  try {
    const j = await getJSON(
      `https://api.weather.gov/alerts/active?point=${place.lat.toFixed(4)},${place.lon.toFixed(4)}`, 9000);
    const feats = (j.features || []).slice(0, 3);
    if (!feats.length) return;
    box.innerHTML = feats.map((f) => {
      const p = f.properties || {};
      const body = (p.description || '').trim();
      return `<div class="alert" data-open="0">
        <div class="ev">${esc(p.event || 'Weather alert')}</div>
        <div class="hd">${esc(p.headline || '')}</div>
        <div class="body" hidden>${esc(body)}</div>
      </div>`;
    }).join('');
    box.hidden = false;
    box.querySelectorAll('.alert').forEach((a) => {
      a.addEventListener('click', () => {
        const b = a.querySelector('.body');
        b.hidden = !b.hidden;
      });
    });
  } catch (e) { /* NWS is US-only and occasionally down; silent by design */ }
}

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/* ------------------------------------------------------------
   Render
   ------------------------------------------------------------ */
function nowIndex() {
  const h = wxData.hourly.time;
  const cur = wxData.current.time.slice(0, 13);
  const i = h.findIndex((t) => t.slice(0, 13) === cur);
  return i < 0 ? 0 : i;
}

function renderAll(stale) {
  renderCurrent();
  renderDaily();
  renderHourly();
  const ago = stale ? 'cached' : clockLocal(lastFetch);
  $('#updated').innerHTML = stale
    ? `<span class="stale">Offline — cached ${clockLocal(lastFetch)}</span>`
    : `Updated ${ago}`;
}

function renderCurrent() {
  const c = wxData.current, d = wxData.daily, h = wxData.hourly, i = nowIndex();
  const label = wxInfo(c.weather_code, c.is_day)[1];
  const uv = h.uv_index[i];
  $('#current').innerHTML = `
    <div class="top">
      ${icon(c.weather_code, c.is_day, 'icon')}
      <div>
        <div class="temp">${Math.round(c.temperature_2m)}<sup>&deg;</sup></div>
        <div class="cond">${label}</div>
        <div class="feels">Feels ${Math.round(c.apparent_temperature)}&deg;</div>
      </div>
      <div class="hilo">
        <div>H <b>${Math.round(d.temperature_2m_max[0])}&deg;</b></div>
        <div>L <b>${Math.round(d.temperature_2m_min[0])}&deg;</b></div>
      </div>
    </div>
    <div class="stats">
      <div class="stat"><div class="k">Wind</div><div class="v">${Math.round(c.wind_speed_10m)}<span class="u"> ${cardinal(c.wind_direction_10m)}</span></div></div>
      <div class="stat"><div class="k">Gust</div><div class="v">${Math.round(c.wind_gusts_10m)}<span class="u"> mph</span></div></div>
      <div class="stat"><div class="k">Humidity</div><div class="v">${Math.round(c.relative_humidity_2m)}<span class="u">%</span></div></div>
      <div class="stat"><div class="k">UV</div><div class="v">${uv == null ? '&mdash;' : Math.round(uv)}</div></div>
    </div>
    <div class="sun">
      <span>Sunrise ${hm12(d.sunrise[0])}</span>
      <span>Sunset ${hm12(d.sunset[0])}</span>
    </div>`;
}

function renderHourly() {
  const h = wxData.hourly;
  const start = nowIndex();
  let from, to, title, note;

  if (selectedDay > 0) {
    const day = wxData.daily.time[selectedDay];
    from = h.time.findIndex((t) => dateOf(t) === day);
    to = from + 24;
    title = 'Hourly &middot; ' + dowOf(day);
    note = 'tap day again for 48h';
  } else {
    from = start;
    to = Math.min(start + 48, h.time.length);
    title = 'Hourly';
    note = 'next 48 h';
  }
  $('#hourlyTitle').innerHTML = title;
  $('#hourlyNote').textContent = note;

  let html = '';
  let lastDate = null;
  for (let i = from; i < to && i < h.time.length; i++) {
    const t = h.time[i];
    if (lastDate && dateOf(t) !== lastDate) html += '<div class="daysep"></div>';
    lastDate = dateOf(t);
    const pp = h.precipitation_probability[i] || 0;
    const isNow = i === start && selectedDay <= 0;
    html += `<div class="hr${isNow ? ' now' : ''}">
      <div class="t">${isNow ? 'Now' : (hourOf(t) === 0 ? dowOf(t) : h12(t))}</div>
      ${icon(h.weather_code[i], h.is_day[i], 'ic')}
      <div class="tp">${Math.round(h.temperature_2m[i])}&deg;</div>
      <div class="pbar"><div class="pfill" style="height:${pp > 0 ? Math.max(8, pp) : 0}%"></div></div>
      <div class="pp">${pp >= 10 ? pp + '%' : ''}</div>
    </div>`;
  }
  const box = $('#hourly');
  box.innerHTML = html;
  box.scrollLeft = 0;
}

function renderDaily() {
  const d = wxData.daily;
  const n = Math.min(5, d.time.length);
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < n; i++) {
    lo = Math.min(lo, d.temperature_2m_min[i]);
    hi = Math.max(hi, d.temperature_2m_max[i]);
  }
  const span = Math.max(1, hi - lo);
  let html = '';
  for (let i = 0; i < n; i++) {
    const l = d.temperature_2m_min[i], hg = d.temperature_2m_max[i];
    const left = ((l - lo) / span) * 100;
    const w = Math.max(6, ((hg - l) / span) * 100);
    const pp = d.precipitation_probability_max[i] || 0;
    html += `<div class="dr${selectedDay === i ? ' sel' : ''}" data-i="${i}">
      <div class="dow">${i === 0 ? 'Today' : dowOf(d.time[i])}</div>
      ${icon(d.weather_code[i], 1, 'ic')}
      <div class="pp">${pp >= 10 ? pp + '%' : ''}</div>
      <div class="lo">${Math.round(l)}&deg;</div>
      <div class="track"><div class="fill" style="left:${left}%;width:${w}%"></div></div>
      <div class="hi">${Math.round(hg)}&deg;</div>
    </div>`;
  }
  const box = $('#daily');
  box.innerHTML = html;
  box.querySelectorAll('.dr').forEach((row) => {
    row.addEventListener('click', () => {
      const i = +row.dataset.i;
      selectedDay = (selectedDay === i || i === 0) ? -1 : i;
      renderDaily();
      renderHourly();
    });
  });
}

/* ------------------------------------------------------------
   Radar — RainViewer frames over a dark CARTO basemap.
   Built lazily the first time the card scrolls into view.
   ------------------------------------------------------------ */
const radar = { map: null, host: '', frames: [], layers: {}, idx: 0, timer: null, playing: false, loaded: false };

// RainViewer's free tiles only exist through zoom 7; anything deeper returns a
// "Zoom Level Not Supported" placeholder. Let Leaflet upscale z7 instead.
const RADAR_NATIVE_Z = 7;
const RADAR_MAX_Z = 10;
const RADAR_Z = 7;

async function initRadar() {
  if (radar.loaded || typeof L === 'undefined') return;
  radar.loaded = true;

  radar.map = L.map('map', {
    zoomControl: false,
    attributionControl: true,
    minZoom: 4,
    maxZoom: RADAR_MAX_Z,
    // Zoom/fade animations leave stacked tile layers behind on some Android
    // builds — two zoom levels drawn at once. Off is also cheaper on battery.
    zoomAnimation: false,
    fadeAnimation: false
  }).setView([place.lat, place.lon], RADAR_Z);

  // Esri dark canvas — keyless, and dark enough that radar colors pop.
  const ESRI = 'https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_';
  L.tileLayer(ESRI + 'Base/MapServer/tile/{z}/{y}/{x}', {
    maxZoom: RADAR_MAX_Z, minZoom: 4, zIndex: 100, className: 'basemap-dim',
    attribution: 'Esri &middot; RainViewer'
  }).addTo(radar.map);
  L.tileLayer(ESRI + 'Reference/MapServer/tile/{z}/{y}/{x}', {
    maxZoom: RADAR_MAX_Z, minZoom: 4, zIndex: 700, opacity: 0.9
  }).addTo(radar.map);

  radar.pin = L.marker([place.lat, place.lon], {
    icon: L.divIcon({ className: '', html: '<div class="mepin"></div>', iconSize: [14, 14], iconAnchor: [7, 7] }),
    interactive: false,
    zIndexOffset: 1000
  }).addTo(radar.map);

  // The card is often still off-screen when the observer fires; make sure
  // Leaflet measures the container it actually ended up with.
  setTimeout(() => radar.map.invalidateSize(), 0);
  window.addEventListener('resize', () => radar.map.invalidateSize());

  await loadFrames();
  radar.map.on('zoomstart', () => stopRadar());
}

async function loadFrames() {
  try {
    const j = await getJSON('https://api.rainviewer.com/public/weather-maps.json', 9000);
    radar.host = j.host;
    const past = (j.radar && j.radar.past) || [];
    const cast = (j.radar && j.radar.nowcast) || [];
    radar.frames = past.slice(-10).concat(cast).map((f) => ({ time: f.time, path: f.path, future: false }));
    for (let i = radar.frames.length - cast.length; i < radar.frames.length; i++) radar.frames[i].future = true;
    if (!radar.frames.length) return;
    const s = $('#radarSlider');
    s.max = radar.frames.length - 1;
    radar.idx = Math.max(0, radar.frames.length - cast.length - 1); // newest observed frame
    s.value = radar.idx;
    showFrame(radar.idx);
    // Parked on the newest observed frame until the play button is pressed.
  } catch (e) {
    $('#radarTime').textContent = 'unavailable';
  }
}

function frameLayer(i) {
  if (radar.layers[i]) return radar.layers[i];
  const f = radar.frames[i];
  const layer = L.tileLayer(`${radar.host}${f.path}/256/{z}/{x}/{y}/4/1_1.png`, {
    opacity: 0, tileSize: 256, maxZoom: RADAR_MAX_Z, maxNativeZoom: RADAR_NATIVE_Z, zIndex: 400 + i
  });
  layer.addTo(radar.map);
  radar.layers[i] = layer;
  return layer;
}

function showFrame(i) {
  if (!radar.frames.length) return;
  radar.idx = i;
  frameLayer(i).setOpacity(0.82);
  Object.keys(radar.layers).forEach((k) => { if (+k !== i) radar.layers[k].setOpacity(0); });
  if (radar.frames[i + 1]) frameLayer(i + 1);          // preload the next one
  const f = radar.frames[i];
  $('#radarTime').textContent = (f.future ? '+' : '') + clockLocal(f.time * 1000);
  $('#radarSlider').value = i;
}

function playRadar() {
  if (radar.playing || radar.frames.length < 2) return;
  radar.playing = true;
  $('#radarPlay').innerHTML = '<svg viewBox="0 0 24 24"><path d="M7 5h4v14H7zM13 5h4v14h-4z"/></svg>';
  radar.timer = setInterval(() => {
    let n = radar.idx + 1;
    if (n >= radar.frames.length) n = 0;
    showFrame(n);
  }, 520);
}
function stopRadar() {
  radar.playing = false;
  clearInterval(radar.timer);
  $('#radarPlay').innerHTML = '<svg viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z"/></svg>';
}

/* ------------------------------------------------------------
   Location
   ------------------------------------------------------------ */
function labelFor(p) {
  return p.region && p.region !== p.name ? `${p.name}, ${shortRegion(p.region)}` : p.name;
}
const STATE_ABBR = {
  Iowa: 'IA', Texas: 'TX', Kansas: 'KS', Missouri: 'MO', Minnesota: 'MN', Illinois: 'IL',
  Nebraska: 'NE', Wisconsin: 'WI', Colorado: 'CO', Arizona: 'AZ', California: 'CA',
  Florida: 'FL', 'New York': 'NY', Oklahoma: 'OK', Arkansas: 'AR', 'South Dakota': 'SD',
  'North Dakota': 'ND', Michigan: 'MI', Indiana: 'IN', Ohio: 'OH', Tennessee: 'TN',
  Kentucky: 'KY', Georgia: 'GA', Alabama: 'AL', Mississippi: 'MS', Louisiana: 'LA'
};
const shortRegion = (r) => STATE_ABBR[r] || r;

function setPlace(p, save) {
  place = p;
  selectedDay = -1;
  $('#placeName').textContent = labelFor(p);
  if (save !== false) {
    try { localStorage.setItem(LS_PLACE, JSON.stringify(p)); } catch (e) {}
    pushRecent(p);
  }
  if (radar.map) {
    radar.map.setView([p.lat, p.lon], RADAR_Z);
    if (radar.pin) radar.pin.setLatLng([p.lat, p.lon]);
  }
  loadWeather(true);
}

function recents() {
  try { return JSON.parse(localStorage.getItem(LS_RECENTS)) || []; } catch (e) { return []; }
}
function pushRecent(p) {
  const list = recents().filter((r) => !(Math.abs(r.lat - p.lat) < 0.02 && Math.abs(r.lon - p.lon) < 0.02));
  list.unshift(p);
  try { localStorage.setItem(LS_RECENTS, JSON.stringify(list.slice(0, 6))); } catch (e) {}
}
function renderRecents() {
  const list = recents();
  $('#recents').innerHTML = list.map((p, i) =>
    `<button class="chip" data-r="${i}">${esc(labelFor(p))}</button>`).join('');
  $('#recents').querySelectorAll('.chip').forEach((b) => {
    b.addEventListener('click', () => { closeSearch(); setPlace(list[+b.dataset.r]); });
  });
}

function useGPS() {
  if (!navigator.geolocation) { toast('No GPS on this browser'); return; }
  toast('Getting location…', 4000);
  navigator.geolocation.getCurrentPosition(async (pos) => {
    const lat = pos.coords.latitude, lon = pos.coords.longitude;
    let p = { name: 'Current location', region: '', country: '', lat, lon };
    try {
      const j = await getJSON(
        `https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${lat.toFixed(4)}&longitude=${lon.toFixed(4)}&localityLanguage=en`, 7000);
      const city = j.city || j.locality || j.principalSubdivision;
      if (city) p = { name: city, region: j.principalSubdivision || '', country: j.countryCode || '', lat, lon };
    } catch (e) { /* name is cosmetic; coordinates are what matter */ }
    $('#toast').hidden = true;
    setPlace(p);
  }, (err) => {
    toast(err.code === 1 ? 'Location permission denied' : 'Could not get location');
  }, { enableHighAccuracy: false, timeout: 10000, maximumAge: 300000 });
}

/* ---- search ---- */
let searchTimer = null;
function openSearch() {
  $('#searchPanel').hidden = false;
  renderRecents();
  $('#searchResults').innerHTML = '';
  $('#searchInput').value = '';
  setTimeout(() => $('#searchInput').focus(), 40);
}
function closeSearch() { $('#searchPanel').hidden = true; }

async function runSearch(q) {
  if (q.trim().length < 2) { $('#searchResults').innerHTML = ''; return; }
  try {
    const j = await getJSON(
      `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(q)}&count=8&language=en&format=json`, 8000);
    const res = j.results || [];
    $('#searchResults').innerHTML = res.length
      ? res.map((r, i) => `<li data-i="${i}">
          <div class="r1">${esc(r.name)}</div>
          <div class="r2">${esc([r.admin1, r.country].filter(Boolean).join(' · '))}</div></li>`).join('')
      : '<li class="r2">No matches</li>';
    $('#searchResults').querySelectorAll('li[data-i]').forEach((li) => {
      li.addEventListener('click', () => {
        const r = res[+li.dataset.i];
        closeSearch();
        setPlace({ name: r.name, region: r.admin1 || '', country: r.country_code || '', lat: r.latitude, lon: r.longitude });
      });
    });
  } catch (e) { toast('Search failed'); }
}

/* ------------------------------------------------------------
   Boot
   ------------------------------------------------------------ */
function boot() {
  $('#placeBtn').addEventListener('click', openSearch);
  $('#searchClose').addEventListener('click', closeSearch);
  $('#gpsBtn').addEventListener('click', useGPS);
  $('#refreshBtn').addEventListener('click', () => loadWeather(true));
  $('#searchInput').addEventListener('input', (e) => {
    clearTimeout(searchTimer);
    const v = e.target.value;
    searchTimer = setTimeout(() => runSearch(v), 280);
  });
  $('#radarPlay').addEventListener('click', () => (radar.playing ? stopRadar() : playRadar()));
  $('#radarSlider').addEventListener('input', (e) => { stopRadar(); showFrame(+e.target.value); });
  $('#radarExpand').addEventListener('click', () => {
    document.body.classList.toggle('radarfull');
    const full = document.body.classList.contains('radarfull');
    $('#radarExpand').textContent = full ? 'Close' : 'Expand';
    if (radar.map) setTimeout(() => radar.map.invalidateSize(), 220);
  });

  // Restore the last place, then paint cached data instantly so the app
  // is useful before the network answers.
  let saved = null;
  try { saved = JSON.parse(localStorage.getItem(LS_PLACE)); } catch (e) {}
  place = saved && typeof saved.lat === 'number' ? saved : DEFAULT_PLACE;
  $('#placeName').textContent = labelFor(place);
  if (!recents().length) pushRecent(DEFAULT_PLACE);

  try {
    const c = JSON.parse(localStorage.getItem(LS_CACHE));
    if (c && c.place && Math.abs(c.place.lat - place.lat) < 0.02) {
      wxData = c.data;
      lastFetch = c.t;
      renderAll(true);
    }
  } catch (e) {}

  loadWeather(false);

  // Radar costs data and battery — only build it once it's on screen.
  const io = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) { io.disconnect(); initRadar(); }
  }, { rootMargin: '120px' });
  io.observe($('#map'));

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      if (Date.now() - lastFetch > STALE_MS) loadWeather(false);
    } else {
      stopRadar();
    }
  });

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
  }
}

document.addEventListener('DOMContentLoaded', boot);
