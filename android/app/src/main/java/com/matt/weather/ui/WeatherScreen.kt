package com.matt.weather.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matt.weather.UiState
import com.matt.weather.data.Fmt
import com.matt.weather.data.Place
import com.matt.weather.data.RadarFrame
import kotlinx.coroutines.delay

@Composable
fun WeatherScreen(
    state: UiState,
    results: List<Place>,
    onRefresh: () -> Unit,
    onGps: () -> Unit,
    onQuery: (String) -> Unit,
    onPick: (Place) -> Unit,
    onSelectDay: (Int) -> Unit,
    onToggleDays: () -> Unit,
    onClearMessage: () -> Unit
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var fullRadar by rememberSaveable { mutableStateOf(false) }
    var radarIdx by rememberSaveable { mutableIntStateOf(0) }
    // Parked on the newest observed frame until the play button is pressed.
    var playing by rememberSaveable { mutableStateOf(false) }

    val frames = state.radar?.frames.orEmpty()

    // The frame list radarIdx was last placed against.
    var shownFrames by remember { mutableStateOf<List<RadarFrame>>(emptyList()) }

    // First load lands on the newest observed frame, not the end of the
    // nowcast. A refresh that rebuilt the list keeps the user's place.
    LaunchedEffect(state.radar) {
        val r = state.radar
        if (r != null && frames.isNotEmpty()) {
            radarIdx = r.idxAfterRefresh(shownFrames, radarIdx)
            shownFrames = frames
        }
    }

    LaunchedEffect(state.message) {
        if (state.message != null && !state.message.endsWith("…")) {
            delay(2600)
            onClearMessage()
        }
    }

    if (searching) {
        BackHandler { searching = false }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            SearchScreen(
                recents = state.recents,
                results = results,
                onQuery = onQuery,
                onPick = { searching = false; onPick(it) },
                onClose = { searching = false }
            )
        }
        return
    }

    if (fullRadar) {
        BackHandler { fullRadar = false }
        Column(
            Modifier
                .fillMaxSize()
                .background(Wx.Bg)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(10.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RADAR", color = Wx.Fg2, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Close", color = Wx.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { fullRadar = false }.padding(8.dp)
                )
            }
            RadarSection(
                place = state.place,
                radar = state.radar,
                cells = state.cells,
                idx = radarIdx,
                playing = playing,
                onIdx = { radarIdx = it; playing = false },
                onNext = { if (frames.isNotEmpty()) radarIdx = (radarIdx + 1) % frames.size },
                onPlayToggle = { playing = !playing },
                mapModifier = Modifier.weight(1f)
            )
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Wx.Bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {

            // ---- top bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Wx.Bg)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { searching = true }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        state.place.label,
                        color = Wx.Fg, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown, null,
                        tint = Wx.Fg3, modifier = Modifier.size(20.dp)
                    )
                }
                IconBox(onClick = onGps) {
                    Icon(Icons.Filled.LocationOn, "Use my location", tint = Wx.Fg, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(6.dp))
                IconBox(onClick = onRefresh) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            color = Wx.Accent, strokeWidth = 2.dp,
                            modifier = Modifier.size(19.dp)
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = Wx.Fg, modifier = Modifier.size(21.dp))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.Line))

            val f = state.forecast
            // Index of the hour the API calls "now"; -1 when there is no data.
            val nowIdx = remember(f) {
                if (f == null) 0 else {
                    val cur = f.current.time.take(13)
                    f.hours.indexOfFirst { it.time.take(13) == cur }.coerceAtLeast(0)
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.alerts.isNotEmpty()) {
                    item {
                        Column { state.alerts.forEach { AlertItem(it) } }
                    }
                }

                if (f == null) {
                    item {
                        WxCard {
                            Text(
                                if (state.failed)
                                    "Could not reach the forecast.\nCheck signal and hit refresh."
                                else "Loading…",
                                color = Wx.Fg2, fontSize = 14.sp, lineHeight = 20.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)
                            )
                        }
                    }
                } else {
                    item { CurrentCard(f, nowIdx) }

                    item {
                        val sel = state.selectedDay
                        val day = f.days.getOrNull(sel)
                        val from = if (sel > 0 && day != null) {
                            f.hours.indexOfFirst { Fmt.dateOf(it.time) == day.date }.coerceAtLeast(0)
                        } else nowIdx
                        val to = if (sel > 0 && day != null) from + 24 else from + 48
                        HourlyCard(
                            hours = f.hours,
                            from = from,
                            to = to,
                            nowIndex = if (sel > 0) -1 else nowIdx,
                            title = if (sel > 0 && day != null) "Hourly · ${Fmt.dow(day.date)} ${Fmt.dom(day.date)}" else "Hourly",
                            note = if (sel > 0) "tap day again for 48h" else "next 48 h"
                        )
                    }

                    item {
                        DailyCard(f.days, state.dayCount, state.selectedDay, onSelectDay, onToggleDays)
                    }
                }

                item {
                    WxCard {
                        CardHead("Radar", null) {
                            Text(
                                "Expand", color = Wx.Accent, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { fullRadar = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                        RadarSection(
                            place = state.place,
                            radar = state.radar,
                            cells = state.cells,
                            idx = radarIdx,
                            playing = playing,
                            onIdx = { radarIdx = it; playing = false },
                            onNext = { if (frames.isNotEmpty()) radarIdx = (radarIdx + 1) % frames.size },
                            onPlayToggle = { playing = !playing },
                            mapModifier = Modifier.height(270.dp)
                        )
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when {
                                state.fetchedAt == 0L -> ""
                                state.stale -> "Offline — cached ${Fmt.clockDevice(state.fetchedAt)}"
                                else -> "Updated ${Fmt.clockDevice(state.fetchedAt)}"
                            },
                            color = if (state.stale) Wx.Warm else Wx.Fg3, fontSize = 11.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text("Open-Meteo · IEM · NWS · Esri", color = Wx.Fg3, fontSize = 11.sp)
                    }
                }
            }
        }

        if (state.message != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 24.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Wx.Card2)
                    .border(1.dp, Wx.Line, RoundedCornerShape(11.dp))
                    .padding(horizontal = 17.dp, vertical = 11.dp)
            ) {
                Text(state.message, color = Wx.Fg, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun IconBox(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Wx.Card)
            .border(1.dp, Wx.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
