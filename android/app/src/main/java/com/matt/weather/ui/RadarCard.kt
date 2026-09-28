package com.matt.weather.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.matt.weather.data.Fmt
import com.matt.weather.data.Net
import com.matt.weather.data.Place
import com.matt.weather.data.RadarIndex
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * RainViewer's free tiles only exist through zoom 7 — deeper zooms return a
 * "Zoom Level Not Supported" placeholder, so the radar source is capped there
 * and osmdroid upscales it. The map itself stops at 10.
 */
private const val RADAR_NATIVE_Z = 7
private const val MAP_MAX_Z = 10.0
private const val MAP_MIN_Z = 4.0
private const val START_Z = 7.0
private const val FRAME_MS = 520L

private val MapVoid = Color(0xFF0B1017)
private const val ESRI = "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/"

/** Esri's tile endpoints are z/y/x, not the usual z/x/y. */
private class EsriSource(name: String, service: String) : OnlineTileSourceBase(
    name, 4, 13, 256, "", arrayOf(ESRI + service + "/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
}

private class RadarSource(name: String, private val prefix: String) : OnlineTileSourceBase(
    name, 4, RADAR_NATIVE_Z, 256, ".png", arrayOf(prefix)
) {
    override fun getTileURLString(i: Long): String =
        prefix + "/256/" + MapTileIndex.getZoom(i) + "/" +
            MapTileIndex.getX(i) + "/" + MapTileIndex.getY(i) + "/4/1_1.png"
}

private class PinOverlay(var lat: Double, var lon: Double) : Overlay() {
    private val fill = Paint().apply { isAntiAlias = true; color = 0xFF57A9FF.toInt() }
    private val ring = Paint().apply {
        isAntiAlias = true
        color = AColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    override fun draw(pCanvas: Canvas, pProjection: Projection) {
        val pt = pProjection.toPixels(GeoPoint(lat, lon), null)
        pCanvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 9f, fill)
        pCanvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 11f, ring)
    }
}

private fun alphaFilter(a: Float) = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, a, 0f
        )
    )
)

/** Esri's "dark gray" canvas is mid-gray; knock it back so radar reads first. */
private fun dimFilter(k: Float) = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            k, 0f, 0f, 0f, 0f,
            0f, k, 0f, 0f, 0f,
            0f, 0f, k, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

private class RadarState {
    var frameKey = ""
    var frames: List<TilesOverlay> = emptyList()
    var pin: PinOverlay? = null
    var placeKey = ""
    var lastIdx = -1
}

private fun transparentTiles(o: TilesOverlay) = o.apply {
    loadingBackgroundColor = AColor.TRANSPARENT
    loadingLineColor = AColor.TRANSPARENT
}

private val FrameShown = alphaFilter(0.85f)
private val FrameHidden = alphaFilter(0f)

/**
 * Only the map's own provider tells the map when a tile lands. An overlay's
 * provider has to be wired up by hand, or its tiles sit downloaded and
 * undrawn until something else happens to repaint the map.
 */
private fun overlayProvider(map: MapView, source: OnlineTileSourceBase) =
    MapTileProviderBasic(map.context, source).apply {
        tileRequestCompleteHandlers.add(map.tileRequestCompleteHandler)
    }

private fun buildMap(ctx: Context): MapView {
    Configuration.getInstance().apply {
        load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        userAgentValue = Net.UA
        osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        // One overlay per radar frame means one tile provider per frame; keep
        // each provider's pools small so a 13-frame loop is not 100+ threads.
        tileDownloadThreads = 2.toShort()
        tileFileSystemThreads = 2.toShort()
    }

    return MapView(ctx).apply {
        setTileSource(EsriSource("EsriDarkBase", "World_Dark_Gray_Base"))
        setMultiTouchControls(true)
        setUseDataConnection(true)
        isTilesScaledToDpi = true
        minZoomLevel = MAP_MIN_Z
        maxZoomLevel = MAP_MAX_Z
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        controller.setZoom(START_Z)
        overlayManager.tilesOverlay.setColorFilter(dimFilter(0.52f))
        overlayManager.tilesOverlay.loadingBackgroundColor = 0xFF0B1017.toInt()
        overlayManager.tilesOverlay.loadingLineColor = 0xFF121A23.toInt()
        tag = RadarState()
    }
}

private fun syncMap(map: MapView, place: Place, radar: RadarIndex?, idx: Int) {
    val ctx = map.context
    val st = map.tag as? RadarState ?: return

    val placeKey = "${place.lat},${place.lon}"
    if (st.placeKey != placeKey) {
        st.placeKey = placeKey
        map.controller.setZoom(START_Z)
        map.controller.setCenter(GeoPoint(place.lat, place.lon))
        st.pin?.let { it.lat = place.lat; it.lon = place.lon }
    }

    val key = radar?.frames?.joinToString(",") { it.path }.orEmpty()
    if (key != st.frameKey) {
        st.frameKey = key
        map.overlays.clear()

        // Every frame stays enabled and is hidden with a zero-alpha filter
        // instead. A disabled overlay never asks for its tiles, which left the
        // first loop blank while each frame fetched on its first showing.
        st.frames = radar?.frames?.mapIndexed { i, f ->
            transparentTiles(
                TilesOverlay(
                    overlayProvider(map, RadarSource("rv$i", radar.host + f.path)), ctx
                )
            ).apply { setColorFilter(FrameHidden) }
        }.orEmpty()
        st.frames.forEach { map.overlays.add(it) }

        // Place labels ride on top of the radar so towns stay readable.
        map.overlays.add(
            transparentTiles(
                TilesOverlay(
                    overlayProvider(map, EsriSource("EsriDarkRef", "World_Dark_Gray_Reference")),
                    ctx
                )
            )
        )

        val pin = PinOverlay(place.lat, place.lon)
        st.pin = pin
        map.overlays.add(pin)

        st.lastIdx = -1
    }

    if (idx != st.lastIdx) {
        st.lastIdx = idx
        st.frames.forEachIndexed { i, o ->
            o.setColorFilter(if (i == idx) FrameShown else FrameHidden)
        }
    }
    map.invalidate()
}

@Composable
fun RadarSection(
    place: Place,
    radar: RadarIndex?,
    idx: Int,
    playing: Boolean,
    onIdx: (Int) -> Unit,
    onNext: () -> Unit,
    onPlayToggle: () -> Unit,
    mapModifier: Modifier
) {
    val frames = radar?.frames.orEmpty()

    LaunchedEffect(playing, frames.size) {
        if (!playing || frames.size < 2) return@LaunchedEffect
        while (true) {
            delay(FRAME_MS)
            onNext()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MapVoid)
    ) {
        AndroidView(
            factory = { buildMap(it) },
            update = { syncMap(it, place, radar, idx) },
            onRelease = { it.onDetach() },
            modifier = mapModifier.fillMaxWidth()
        )

        Row(
            Modifier
                .fillMaxWidth()
                .background(Wx.Card2)
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Wx.Card)
                    .clickable(enabled = frames.size > 1) { onPlayToggle() },
                contentAlignment = Alignment.Center
            ) {
                if (playing) {
                    Row {
                        Box(Modifier.width(5.dp).height(16.dp).background(Wx.Fg))
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.width(5.dp).height(16.dp).background(Wx.Fg))
                    }
                } else {
                    Icon(
                        Icons.Filled.PlayArrow, "Play radar loop",
                        tint = Wx.Fg, modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Slider(
                value = idx.toFloat(),
                onValueChange = { onIdx(it.toInt()) },
                valueRange = 0f..maxOf(1, frames.size - 1).toFloat(),
                // Continuous: discrete steps would stipple the track with ticks.
                steps = 0,
                enabled = frames.size > 1,
                colors = SliderDefaults.colors(
                    thumbColor = Wx.Accent,
                    activeTrackColor = Wx.Accent,
                    inactiveTrackColor = Wx.Track
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                frames.getOrNull(idx)?.let {
                    (if (it.future) "+" else "") + Fmt.clockDevice(it.time * 1000L)
                } ?: if (radar == null) "…" else "—",
                color = Wx.Fg2, fontSize = 12.sp,
                modifier = Modifier.width(64.dp)
            )
        }
    }
}
