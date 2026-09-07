package com.matt.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matt.weather.data.Alert
import com.matt.weather.data.Day
import com.matt.weather.data.Fmt
import com.matt.weather.data.Forecast
import com.matt.weather.data.Hour
import com.matt.weather.data.Wmo
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun WxCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Wx.Card)
            .border(1.dp, Wx.Line, RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
fun CardHead(title: String, note: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(),
            color = Wx.Fg2, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
        )
        Spacer(Modifier.weight(1f))
        if (note != null) Text(note, color = Wx.Fg3, fontSize = 12.sp)
        trailing?.invoke()
    }
}

/* ---------------------------------------------------------------- alerts */

@Composable
fun AlertItem(alert: Alert) {
    var open by remember(alert) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Wx.AlertBg)
            .border(1.dp, Wx.AlertLine, RoundedCornerShape(14.dp))
            .clickable { open = !open }
    ) {
        Row {
            Box(Modifier.width(4.dp).fillMaxHeight().background(Wx.AlertBar))
            Column(Modifier.padding(11.dp)) {
                Text(alert.event, color = Wx.AlertFg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (alert.headline.isNotBlank()) {
                    Text(
                        alert.headline, color = Wx.AlertFg2, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (open && alert.body.isNotBlank()) {
                    Text(
                        alert.body, color = Wx.AlertFg2, fontSize = 13.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/* --------------------------------------------------------------- current */

@Composable
fun CurrentCard(f: Forecast, nowIndex: Int) {
    val c = f.current
    val today = f.days.firstOrNull()
    val uv = f.hours.getOrNull(nowIndex)?.uv

    WxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(c.code, c.isDay, Modifier.size(76.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    bigTemp(c.temp),
                    color = Wx.Fg, fontSize = 62.sp, lineHeight = 62.sp,
                    fontWeight = FontWeight.Light
                )
                Text(
                    Wmo.label(c.code), color = Wx.Fg, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)
                )
                Text("Feels ${Fmt.temp(c.apparent)}", color = Wx.Fg2, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            if (today != null) {
                Column(horizontalAlignment = Alignment.End) {
                    HiLo("H", today.max)
                    HiLo("L", today.min)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Wx.Line)
        ) {
            Stat("Wind", c.wind.roundToInt().toString(), " ${Fmt.cardinal(c.windDir)}", Modifier.weight(1f))
            Divider1()
            Stat("Gust", c.gust.roundToInt().toString(), " mph", Modifier.weight(1f))
            Divider1()
            Stat("Humidity", c.humidity.toString(), "%", Modifier.weight(1f))
            Divider1()
            Stat("UV", uv?.roundToInt()?.toString() ?: "—", "", Modifier.weight(1f))
        }

        if (today != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Sunrise ${Fmt.hourMin12(today.sunrise)}", color = Wx.Fg3, fontSize = 12.sp)
                Text("Sunset ${Fmt.hourMin12(today.sunset)}", color = Wx.Fg3, fontSize = 12.sp)
            }
        }
    }
}

private fun bigTemp(v: Double): AnnotatedString = buildAnnotatedString {
    append(v.roundToInt().toString())
    withStyle(SpanStyle(fontSize = 24.sp, baselineShift = BaselineShift.Superscript)) {
        append("°")
    }
}

@Composable
private fun HiLo(k: String, v: Double) {
    Row {
        Text("$k ", color = Wx.Fg2, fontSize = 14.sp)
        Text(Fmt.temp(v), color = Wx.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Divider1() = Box(Modifier.width(1.dp).height(52.dp).background(Wx.Line))

@Composable
private fun Stat(k: String, v: String, unit: String, modifier: Modifier) {
    Column(
        modifier.background(Wx.Card2).padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(k.uppercase(), color = Wx.Fg3, fontSize = 10.sp, letterSpacing = 0.7.sp)
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Bottom) {
            Text(v, color = Wx.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (unit.isNotEmpty()) Text(unit, color = Wx.Fg3, fontSize = 11.sp)
        }
    }
}

/* ---------------------------------------------------------------- hourly */

@Composable
fun HourlyCard(hours: List<Hour>, from: Int, to: Int, nowIndex: Int, title: String, note: String) {
    WxCard {
        CardHead(title, note)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            var lastDate: String? = null
            for (i in from until minOf(to, hours.size)) {
                val h = hours[i]
                val d = Fmt.dateOf(h.time)
                if (lastDate != null && d != lastDate) {
                    Box(
                        Modifier
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                            .width(1.dp).height(86.dp).background(Wx.Line)
                    )
                }
                lastDate = d
                HourCell(h, isNow = i == nowIndex)
            }
        }
    }
}

@Composable
private fun HourCell(h: Hour, isNow: Boolean) {
    Column(
        Modifier
            .width(54.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (isNow) Wx.Card2 else Wx.Card)
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (isNow) "Now" else if (Fmt.hourOf(h.time) == 0) Fmt.dow(h.time) else Fmt.hour12(h.time),
            color = if (isNow) Wx.Accent else Wx.Fg2,
            fontSize = 11.sp,
            fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal
        )
        WeatherIcon(h.code, h.isDay, Modifier.padding(top = 5.dp, bottom = 3.dp).size(26.dp))
        Text(Fmt.temp(h.temp), color = Wx.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier
                .padding(top = 5.dp)
                .width(16.dp).height(26.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Wx.Track),
            contentAlignment = Alignment.BottomCenter
        ) {
            if (h.pop > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((26f * max(0.08f, h.pop / 100f)).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Wx.Wet)
                )
            }
        }
        // A blank space rather than "" so cells without a % keep the same height.
        Text(
            if (h.pop >= 10) "${h.pop}%" else " ",
            color = Wx.Cold, fontSize = 10.sp, lineHeight = 12.sp,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/* ----------------------------------------------------------------- daily */

@Composable
fun DailyCard(days: List<Day>, selected: Int, onSelect: (Int) -> Unit) {
    val shown = days.take(5)
    if (shown.isEmpty()) return
    val lo = shown.minOf { it.min }
    val hi = shown.maxOf { it.max }
    val span = max(1.0, hi - lo)

    WxCard {
        CardHead("5 Day", "tap a day")
        shown.forEachIndexed { i, d ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.Line))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected == i) Wx.Card2 else Wx.Card)
                    .clickable { onSelect(i) }
                    .padding(vertical = 11.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (i == 0) "Today" else Fmt.dow(d.date),
                    color = Wx.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(52.dp)
                )
                WeatherIcon(d.code, true, Modifier.size(28.dp))
                Text(
                    if (d.popMax >= 10) "${d.popMax}%" else "",
                    color = Wx.Cold, fontSize = 12.sp,
                    modifier = Modifier.width(44.dp).padding(start = 8.dp)
                )
                Text(
                    Fmt.temp(d.min), color = Wx.Fg3, fontSize = 15.sp,
                    textAlign = TextAlign.End, modifier = Modifier.width(36.dp)
                )
                Row(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 9.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Wx.Track)
                ) {
                    val left = ((d.min - lo) / span).toFloat().coerceIn(0f, 1f)
                    val width = ((d.max - d.min) / span).toFloat().coerceIn(0.06f, 1f - left)
                    val right = (1f - left - width).coerceAtLeast(0f)
                    if (left > 0f) Spacer(Modifier.weight(left))
                    Box(
                        Modifier
                            .weight(width)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(listOf(Wx.Cold, Wx.Warm)))
                    )
                    if (right > 0f) Spacer(Modifier.weight(right))
                }
                Text(
                    Fmt.temp(d.max), color = Wx.Fg, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp)
                )
            }
        }
    }
}
