'use strict';

/* ============================================================
   Weather — hourly / 10-day / radar. No ads, no trackers.
   Data: Open-Meteo (forecast + geocoding), NWS (alerts),
         Iowa State IEM (radar, HRRR, storm cells), Esri (basemap).
   No API keys anywhere. All requests are anonymous GETs.
   ============================================================ */

const DEFAULT_PLACE = { name: 'Ames', region: 'Iowa', country: 'US', lat: 42.0308, lon: -93.6319 };
const LS_PLACE = 'wx.place';
const LS_RECENTS = 'wx.recents';
const LS_CACHE = 'wx.cache';
const LS_DAYS = 'wx.days';
const STALE_MS = 10 * 60 * 1000;

const $ = (s) => document.querySelector(s);
const pad2 = (n) => (n < 10 ? '0' + n : '' + n);
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

let place = null;
let wxData = null;
let selectedDay = -1;      // -1 = rolling 48h view
let dayCount = 10;         // daily card length: 10 or 5
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
// Day of the month, "5". Ten days holds the same weekday twice, so a bare
// "Mon" is not enough to tell the rows apart.
const domOf = (iso) => parseInt(iso.slice(8, 10), 10);
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
    forecast_days: '10'
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

// api.weather.gov answers HTTP 400 outside its coverage, so the request is
// only made inside one of these boxes: lower 48, Alaska, Hawaii, Puerto
// Rico / USVI, Guam / CNMI. Boxes, not borders — a Canadian or Mexican point
// in the lower-48 box still asks, and its error reads as "no alerts".
const NWS_BOXES = [
  [24, 50, -125, -66], [51, 72, -180, -129], [18, 23, -161, -154], [17, 19, -68, -64], [13, 21, 144, 146]
];
const inNwsBox = (lat, lon) =>
  NWS_BOXES.some((b) => lat >= b[0] && lat <= b[1] && lon >= b[2] && lon <= b[3]);

async function loadAlerts() {
  const box = $('#alerts');
  box.hidden = true;
  box.innerHTML = '';
  if (!inNwsBox(place.lat, place.lon)) return;
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
  } catch (e) { /* any HTTP error = no alerts; NWS is occasionally down; silent by design */ }
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
    title = 'Hourly &middot; ' + dowOf(day) + ' ' + domOf(day);
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
  const n = Math.min(dayCount, d.time.length);
  $('#dailyTitle').textContent = dayCount + ' Day';
  $('#dailyToggle').textContent = dayCount === 10 ? '5 day' : '10 day';
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
      <div class="dow">${i === 0 ? 'Today' : dowOf(d.time[i]) + ' <span class="dt">' + domOf(d.time[i]) + '</span>'}</div>
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
   Radar — Iowa State IEM frames over a dark Esri basemap.
   Past = NEXRAD composite (observed), future = HRRR simulated
   reflectivity (model), both on the NWS color scale.
   Built lazily the first time the card scrolls into view.
   ------------------------------------------------------------ */
const radar = {
  map: null, frames: [], layers: {}, idx: 0, timer: null, playing: false, loaded: false,
  cells: [], cellsFor: null, tracks: null,
  loadedAt: 0,        // last successful frame index fetch, ms
  refreshTimer: null  // runs only while the page is visible
};
// Frames and cells are re-fetched this often while the page is on screen,
// and on return to it once the last load is older than this.
const RADAR_REFRESH_MS = 5 * 60 * 1000;

// IEM serves both products through z10, so the map never upscales radar.
const RADAR_NATIVE_Z = 10;
const RADAR_MAX_Z = 10;
const RADAR_Z = 7;
const IEM = 'https://mesonet.agron.iastate.edu/';
const IEM_TILES = IEM + 'cache/tile.py/1.0.0/';
const PAST_FRAMES = 10, PAST_STEP_MIN = 10, CAST_FRAMES = 12, HRRR_STEP_MIN = 15, HRRR_MAX_MIN = 1080;
// The US composite and HRRR only cover the lower 48 and a margin around it.
const inRadarBox = (lat, lon) => lat >= 21 && lat <= 53 && lon >= -130 && lon <= -60;

// "202609281925" — the UTC stamp IEM puts in composite layer names.
function utcStamp(ms) {
  const d = new Date(ms);
  return d.getUTCFullYear() + pad2(d.getUTCMonth() + 1) + pad2(d.getUTCDate()) +
    pad2(d.getUTCHours()) + pad2(d.getUTCMinutes());
}
const pad4 = (n) => ('000' + n).slice(-4);
const hrrrMetaURL = (m) => `${IEM}data/gis/images/4326/hrrr/refd_${pad4(m)}.json`;

/* ---- frame lists: pure, no DOM, no Leaflet ---- */
// While a new HRRR run is landing, two frames can come from different runs
// and share a valid time. Keep the newer run's; v is the fixed-width init
// stamp, so a string compare orders it.
function dedupeCast(cast) {
  const byTime = new Map();
  cast.forEach((f) => {
    const had = byTime.get(f.time);
    if (!had || f.v > had.v) byTime.set(f.time, f);
  });
  return [...byTime.values()].sort((a, b) => a.time - b.time);
}
// Same layers and cache stamps = same picture.
const framesKey = (frames) => frames.map((f) => f.layer + '|' + f.v).join(',');
const newestObserved = (frames) => Math.max(0, frames.map((f) => f.future).lastIndexOf(false));
// Where to stand after the frame list is rebuilt: parked on the newest
// observed frame stays parked there; anything else keeps the nearest time.
function idxAfterRefresh(oldFrames, oldIdx, frames) {
  if (!oldFrames.length || !oldFrames[oldIdx] || oldIdx === newestObserved(oldFrames)) return newestObserved(frames);
  const t = oldFrames[oldIdx].time;
  let best = 0;
  frames.forEach((f, i) => { if (Math.abs(f.time - t) < Math.abs(frames[best].time - t)) best = i; });
  return best;
}

/* ---- storm tracks: pure math, no DOM, no Leaflet ---- */
const KM_PER_KT_HR = 1.852;
const R_EARTH_KM = 6371;
const rad = (d) => d * Math.PI / 180;
const deg = (r) => r * 180 / Math.PI;

// NEXRAD storm attributes give drct as the direction the cell is moving FROM
// (meteorological convention). Checked against the live feed 2026-09-28: 33 of
// 35 cells moved along drct + 180 between two snapshots, 0 along drct.
const stormHeading = (drct) => (drct + 180) % 360;

function distKm(lat1, lon1, lat2, lon2) {
  const a = Math.sin(rad(lat2 - lat1) / 2) ** 2 +
    Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lon2 - lon1) / 2) ** 2;
  return 2 * R_EARTH_KM * Math.asin(Math.min(1, Math.sqrt(a)));
}
// Initial great-circle bearing from point 1 to point 2, 0-360.
function bearingDeg(lat1, lon1, lat2, lon2) {
  const y = Math.sin(rad(lon2 - lon1)) * Math.cos(rad(lat2));
  const x = Math.cos(rad(lat1)) * Math.sin(rad(lat2)) -
    Math.sin(rad(lat1)) * Math.cos(rad(lat2)) * Math.cos(rad(lon2 - lon1));
  return (deg(Math.atan2(y, x)) + 360) % 360;
}
// Point reached going km along a great circle on the given bearing. [lat, lon]
function destPoint(lat, lon, brg, km) {
  const d = km / R_EARTH_KM, b = rad(brg), p1 = rad(lat), l1 = rad(lon);
  const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
  const l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
  return [deg(p2), ((deg(l2) + 540) % 360) - 180];
}
// Where the cell will be after min minutes. [lat, lon]
const cellAt = (c, min) => destPoint(c.lat, c.lon, c.heading, c.sknt * KM_PER_KT_HR * min / 60);

const COMPASS8 = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
const compass8 = (b) => COMPASS8[Math.round((((b % 360) + 360) % 360) / 45) % 8];

// GeoJSON feed -> flat cell list. Anything malformed is skipped.
function parseCells(j) {
  return ((j && j.features) || []).map((f) => {
    const p = f.properties || {}, g = (f.geometry && f.geometry.coordinates) || [];
    return {
      lat: +g[1], lon: +g[0], dbz: +p.max_dbz, sknt: +p.sknt, drct: +p.drct,
      heading: stormHeading(+p.drct), posh: +p.posh || 0,
      tvs: p.tvs || 'NONE', valid: Date.parse(p.valid)
    };
  }).filter((c) => isFinite(c.lat) && isFinite(c.lon) && isFinite(c.dbz) && isFinite(c.sknt) && isFinite(c.drct));
}

// Moving (>= 5 kt), strong (>= 40 dBZ), within 300 km. Neighbouring radars
// report the same storm, so within 12 km only the strongest is kept. Then
// the 40 nearest to the place.
function pickCells(cells, lat, lon) {
  const near = cells
    .filter((c) => c.sknt >= 5 && c.dbz >= 40)
    .map((c) => Object.assign({}, c, { km: distKm(lat, lon, c.lat, c.lon) }))
    .filter((c) => c.km <= 300)
    .sort((a, b) => b.dbz - a.dbz || a.km - b.km);
  const kept = [];
  near.forEach((c) => {
    if (!kept.some((k) => distKm(k.lat, k.lon, c.lat, c.lon) < 12)) kept.push(c);
  });
  return kept.sort((a, b) => a.km - b.km).slice(0, 40);
}

// Closest the 0-60 minute track gets to the place: { km, min }. Sampled every
// quarter minute — under 0.5 km of travel between samples at 60 kt.
function closestApproach(c, lat, lon) {
  let best = { km: Infinity, min: 0 };
  for (let m = 0; m <= 60; m += 0.25) {
    const p = cellAt(c, m), km = distKm(lat, lon, p[0], p[1]);
    if (km < best.km) best = { km, min: m };
  }
  return best;
}

// The soonest cell whose track passes within 8 km, or null.
function arrival(cells, lat, lon) {
  let best = null;
  cells.forEach((c) => {
    const a = closestApproach(c, lat, lon);
    if (a.km > 8) return;
    const t = (isFinite(c.valid) ? c.valid : Date.now()) + a.min * 60000;
    if (!best || t < best.t) best = { c, t };
  });
  if (!best) return null;
  const c = best.c;
  return {
    t: best.t,
    mi: Math.round(distKm(lat, lon, c.lat, c.lon) / 1.609344),
    dir: compass8(bearingDeg(lat, lon, c.lat, c.lon)),
    mph: Math.round(c.sknt * 1.150779)
  };
}
const severe = (c) => c.tvs !== 'NONE' || c.posh >= 50;

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
    attribution: 'Esri &middot; IEM / NWS'
  }).addTo(radar.map);
  // Place labels get their own pane so storm tracks (overlay pane, 400) sit
  // above the radar tiles but under the town names and the pin (600).
  radar.map.createPane('labels').style.zIndex = 450;
  radar.map.getPane('labels').style.pointerEvents = 'none';
  L.tileLayer(ESRI + 'Reference/MapServer/tile/{z}/{y}/{x}', {
    maxZoom: RADAR_MAX_Z, minZoom: 4, pane: 'labels', opacity: 0.9
  }).addTo(radar.map);
  radar.tracks = L.layerGroup().addTo(radar.map);

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
  if (document.visibilityState === 'visible') startRadarRefresh();
  radar.map.on('zoomstart', () => stopRadar());
  // Ticks and arrowheads are sized in screen pixels, so redraw per zoom.
  radar.map.on('zoomend', drawTracks);
}

// HRRR forecast frames after T0. The model init is usually 1-3 h old, and
// the refd_*.json files are overwritten one by one as a new run lands, so
// each frame's own metadata is the only trustworthy time for it.
async function hrrrFrames(t0) {
  try {
    const m0 = await getJSON(hrrrMetaURL(0), 9000);
    const init = Date.parse(m0.model_init_utc);
    const mins = [];
    for (let m = 0; m <= HRRR_MAX_MIN && mins.length < CAST_FRAMES; m += HRRR_STEP_MIN) {
      if (init + m * 60000 > t0) mins.push(m);
    }
    const metas = await Promise.all(mins.map((m) => getJSON(hrrrMetaURL(m), 9000).catch(() => null)));
    return dedupeCast(metas.map((j, i) => {
      if (!j) return null;
      const t = Date.parse(j.model_forecast_utc), run = Date.parse(j.model_init_utc);
      if (!isFinite(t) || !isFinite(run) || t <= t0) return null;
      // Same layer name every run — the init stamp busts the tile cache.
      return { time: t / 1000, layer: `hrrr::REFD-F${pad4(mins[i])}-0`, v: utcStamp(run).slice(0, 10), future: true };
    }).filter(Boolean));
  } catch (e) {
    return []; // no model frames: the loop is past-only
  }
}

function clearFrames() {
  stopRadar();
  Object.keys(radar.layers).forEach((k) => radar.map.removeLayer(radar.layers[k]));
  radar.layers = {};
  radar.frames = [];
}

async function loadFrames() {
  if (!inRadarBox(place.lat, place.lon)) {
    $('#radarTime').textContent = 'US only';
    loadCells();
    return;
  }
  try {
    const j = await getJSON(IEM + 'data/gis/images/4326/USCOMP/n0q_0.json', 9000);
    const t0 = Date.parse(j.meta && j.meta.valid);
    if (!isFinite(t0)) throw new Error('no composite time');
    const past = [];
    for (let k = PAST_FRAMES - 1; k >= 0; k--) {
      const t = t0 - k * PAST_STEP_MIN * 60000;
      past.push({ time: t / 1000, layer: 'ridge::USCOMP-N0Q-' + utcStamp(t), v: '', future: false });
    }
    const frames = past.concat(await hrrrFrames(t0));
    radar.loadedAt = Date.now();
    // An unchanged list leaves the layers alone: no rebuild, no flicker.
    if (framesKey(frames) !== framesKey(radar.frames)) setFrames(frames);
  } catch (e) {
    // A failed refresh keeps a working loop on screen.
    if (!radar.frames.length) $('#radarTime').textContent = 'unavailable';
  }
  loadCells();
}

function setFrames(frames) {
  const wasPlaying = radar.playing;
  // First load lands on the newest observed frame and stays parked there
  // until the play button is pressed.
  const idx = idxAfterRefresh(radar.frames, radar.idx, frames);
  clearFrames();
  radar.frames = frames;
  const s = $('#radarSlider');
  s.max = radar.frames.length - 1;
  s.value = idx;
  showFrame(idx);
  // Every other frame starts fetching its tiles now, hidden, so the first
  // loop has something to draw instead of filling in on the second pass.
  radar.frames.forEach((f, i) => frameLayer(i));
  if (wasPlaying) playRadar();
}

function startRadarRefresh() {
  stopRadarRefresh();
  radar.refreshTimer = setInterval(loadFrames, RADAR_REFRESH_MS);
}
function stopRadarRefresh() {
  clearInterval(radar.refreshTimer);
  radar.refreshTimer = null;
}
// Back on screen: catch up at once if the loop has gone stale, then resume
// the 5-minute cadence.
function resumeRadar() {
  if (!radar.map) return;
  if (Date.now() - radar.loadedAt > RADAR_REFRESH_MS) loadFrames();
  startRadarRefresh();
}

function frameLayer(i) {
  if (radar.layers[i]) return radar.layers[i];
  const f = radar.frames[i];
  const layer = L.tileLayer(IEM_TILES + f.layer + '/{z}/{x}/{y}.png' + (f.v ? '?v=' + f.v : ''), {
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
  const f = radar.frames[i];
  $('#radarTime').textContent = !inRadarBox(place.lat, place.lon) ? 'US only'
    : (f.future ? '+' : '') + clockLocal(f.time * 1000);
  $('#radarSlider').value = i;
}

// Storm cells for the current place. A failed refresh keeps the tracks
// already drawn for this place; a first load that fails has none, silently.
async function loadCells() {
  const p = place;
  let cells = [];
  if (inRadarBox(p.lat, p.lon)) {
    try {
      cells = pickCells(parseCells(await getJSON(IEM + 'geojson/nexrad_attr.geojson', 12000)), p.lat, p.lon);
    } catch (e) {
      if (radar.cellsFor === p) return;
    }
  }
  if (place !== p) return; // the place changed while this was in flight
  radar.cells = cells;
  radar.cellsFor = p;
  drawTracks();
}

// One track per cell: dot, 60-minute line, ticks at 15/30/45, arrowhead.
function drawTracks() {
  if (!radar.tracks) return;
  radar.tracks.clearLayers();
  const map = radar.map, z = map.getZoom();
  radar.cells.forEach((c) => {
    const color = severe(c) ? '#FF5A5A' : '#fff';
    const style = { color, weight: 2, opacity: 0.85, interactive: false };
    const line = [0, 15, 30, 45, 60].map((m) => cellAt(c, m));
    L.polyline(line, style).addTo(radar.tracks);
    L.circleMarker(line[0], { radius: 3, stroke: false, fillColor: color, fillOpacity: 0.85, interactive: false })
      .addTo(radar.tracks);
    // Screen-space direction of the whole track, for ticks and the head.
    const a = map.project(line[0], z), b = map.project(line[4], z);
    const len = a.distanceTo(b);
    if (len < 1) return;
    const ux = (b.x - a.x) / len, uy = (b.y - a.y) / len;
    const at = (p, dx, dy) => map.unproject(L.point(p.x + dx, p.y + dy), z);
    [1, 2, 3].forEach((k) => {
      const p = map.project(line[k], z);
      L.polyline([at(p, -uy * 4, ux * 4), at(p, uy * 4, -ux * 4)], style).addTo(radar.tracks);
    });
    L.polygon([
      at(b, 0, 0),
      at(b, -ux * 8 - uy * 4.5, -uy * 8 + ux * 4.5),
      at(b, -ux * 8 + uy * 4.5, -uy * 8 - ux * 4.5)
    ], { stroke: false, fillColor: color, fillOpacity: 0.85, interactive: false }).addTo(radar.tracks);
  });
  const a = arrival(radar.cells, place.lat, place.lon), el = $('#radarEta');
  el.hidden = !a;
  el.textContent = a ? `Cell ${a.mi} mi ${a.dir}, ${a.mph} mph, reaches here ≈ ${clockLocal(a.t)}` : '';
}

function playRadar() {
  if (radar.playing || radar.frames.length < 2) return;
  radar.playing = true;
  $('#radarPlay').innerHTML = '<svg viewBox="0 0 24 24"><path d="M7 5h4v14H7zM13 5h4v14h-4z"/></svg>';
  radar.timer = setInterval(() => {
    let n = radar.idx + 1;
    if (n >= radar.frames.length) n = 0;
    // Hold on the current frame while the next one's tiles are still in
    // flight (first loop on a slow connection, or right after a pan).
    if (frameLayer(n).isLoading()) return;
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
    // Frames are the same for any US place; only a first load (or a move in
    // from outside the coverage box) needs them fetched. Cells are per place.
    if (!radar.frames.length) loadFrames();
    else { showFrame(radar.idx); loadCells(); }
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
  $('#refreshBtn').addEventListener('click', () => {
    loadWeather(true);
    if (radar.map) loadFrames(); // frames + cells
  });
  $('#searchInput').addEventListener('input', (e) => {
    clearTimeout(searchTimer);
    const v = e.target.value;
    searchTimer = setTimeout(() => runSearch(v), 280);
  });
  try { if (localStorage.getItem(LS_DAYS) === '5') dayCount = 5; } catch (e) {}
  $('#dailyToggle').addEventListener('click', () => {
    dayCount = dayCount === 10 ? 5 : 10;
    try { localStorage.setItem(LS_DAYS, '' + dayCount); } catch (e) {}
    if (!wxData) return;
    if (selectedDay >= dayCount) selectedDay = -1;
    renderDaily();
    renderHourly();
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
      resumeRadar();
    } else {
      stopRadar();
      stopRadarRefresh(); // no timers while hidden
    }
  });
  // Restored from the back/forward cache: no visibilitychange on some builds.
  window.addEventListener('pageshow', (e) => { if (e.persisted) resumeRadar(); });

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
  }
}

document.addEventListener('DOMContentLoaded', boot);
