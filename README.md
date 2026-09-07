# Weather

A phone-sized weather app: current conditions, 48-hour hourly, 5-day, and animated
radar. No ads, no trackers, no analytics, no cookie banner, no account, no API keys.
One HTML file, one CSS file, one JS file.

Accuracy is ballpark by design — this is a "do I need a jacket / is that cell going
to hit Veenker" app, not a life-safety product. Watches and warnings come straight
from the National Weather Service and are the one thing here worth trusting.

---

## What's on the screen

| Section | What it shows |
|---|---|
| Alerts | Active NWS watches/warnings for the exact point. Tap one to read the full text. Hidden when there are none. |
| Current | Temp, condition, feels-like, today's high/low, wind + direction, gust, humidity, UV, sunrise/sunset. |
| Hourly | Next 48 hours. Horizontal scroll. Blue bar under each hour = chance of precip. |
| 5 Day | Day, icon, max chance of precip, and a low→high bar scaled across the whole 5 days. **Tap a day** to swap the hourly strip to that day; tap again for the rolling 48 h. |
| Radar | 10 past frames (~1 hr) plus RainViewer's 30-minute nowcast. Auto-plays. Drag the slider to scrub, `Expand` for fullscreen, pinch to zoom. A `+` on the timestamp means it's a forecast frame, not an observation. |

Location: tap the place name to search any city, or the crosshair to use GPS.
The last six places you looked at stay as chips in the search screen. Defaults to
Ames, IA on a fresh install.

---

## Install on your phone

**On your phone**, in Chrome:

1. Open the app URL.
2. Menu (⋮) → **Add to Home screen**.
3. It launches fullscreen with no browser chrome, and opens instantly from cache.

The last forecast is kept on the device. With no signal it opens and shows the
cached data with an orange **Offline — cached 2:14pm** line at the bottom instead
of pretending the numbers are current.

---

## Deploy to GitHub Pages

**In a browser, on any machine:**

1. Go to <https://github.com/postmaster87/matts-weather-app/settings/pages>
2. **Source** → *Deploy from a branch*
3. **Branch** → `main`, folder `/ (root)` → **Save**
4. Wait ~1 minute. The app is live at
   <https://postmaster87.github.io/matts-weather-app/>

There is no build step. Push to `main` and it deploys.

---

## Data sources

| Source | Used for | Key needed |
|---|---|---|
| [Open-Meteo](https://open-meteo.com/) | Forecast (hourly + daily) | No |
| [Open-Meteo Geocoding](https://open-meteo.com/en/docs/geocoding-api) | Place search | No |
| [api.weather.gov](https://www.weather.gov/documentation/services-web-api) | NWS alerts (US only) | No |
| [RainViewer](https://www.rainviewer.com/api.html) | Radar tiles + nowcast | No |
| [Esri dark canvas](https://services.arcgisonline.com/) | Radar basemap | No |
| [BigDataCloud](https://www.bigdatacloud.com/) | Reverse geocode for the GPS button's label | No |
| [Leaflet 1.9.4](https://leafletjs.com/) (cdnjs) | Map | No |

Nothing leaves the phone except the coordinates of the place being looked up.
There is no server, no database, and no third-party script beyond Leaflet.

---

## Known limits

- **Radar detail caps at zoom 7.** RainViewer's free tiles don't exist past z7 —
  deeper zooms return a "Zoom Level Not Supported" placeholder. The app pins the
  radar layer at z7 and lets Leaflet upscale, so zooming past regional view gets
  blocky rather than blank. Map zoom is capped at 10.
- **Alerts are US-only.** `api.weather.gov` returns nothing outside the US; the
  alert strip just stays hidden.
- **Radar timestamps are in the phone's timezone**, everything else is in the
  forecast location's local time. Only matters when looking at another time zone.
- **Daily icon vs. daily precip %** can look inconsistent (an overcast icon next
  to 89%). Both come from Open-Meteo as-is: the icon is the day's dominant
  condition, the % is the single wettest hour. Not smoothed over.
- Open-Meteo is a model blend, not a nowcast. Treat the hourly numbers as
  directionally right, not exact.

---

## Files

```
index.html              markup
app.css                 all styling, dark theme, phone-first
app.js                  everything else — data, icons, render, radar
sw.js                   service worker, caches the shell only
manifest.webmanifest    PWA manifest
icons/                  192 / 512 / maskable-512 PNGs
```

Local preview:

```bash
python -m http.server 8765
```

Then open `http://localhost:8765`. A service worker is registered, so after
editing `app.js` or `app.css`, hard-reload (or bump `CACHE` in `sw.js`) to be
sure you're seeing the new file.
