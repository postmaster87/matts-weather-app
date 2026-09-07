package com.matt.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import com.matt.weather.data.Sky
import com.matt.weather.data.Wmo
import kotlin.math.cos
import kotlin.math.sin

/**
 * The condition icons, drawn rather than shipped as assets — same 24-unit
 * geometry as the web build so both versions look identical.
 */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, modifier: Modifier = Modifier) {
    val sky = Wmo.sky(code)
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val ox = (size.width - 24f * s) / 2f
        val oy = (size.height - 24f * s) / 2f

        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)

        fun dot(cx: Float, cy: Float, r: Float, c: Color) = drawCircle(c, r * s, p(cx, cy))

        fun bar(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) = drawRoundRect(
            color = c,
            topLeft = p(x, y),
            size = Size(w * s, h * s),
            cornerRadius = CornerRadius(r * s, r * s)
        )

        fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, c: Color) = drawLine(
            color = c, start = p(x0, y0), end = p(x1, y1),
            strokeWidth = w * s, cap = StrokeCap.Round
        )

        fun rays(cx: Float, cy: Float, r0: Float, r1: Float, w: Float) {
            var a = 0
            while (a < 360) {
                val t = Math.toRadians(a.toDouble())
                val dx = cos(t).toFloat()
                val dy = sin(t).toFloat()
                stroke(cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, w, Wx.Sun)
                a += 45
            }
        }

        /** Cloud, scaled about (12,11) then nudged — matches the SVG transform. */
        fun cloud(c: Color, dx: Float = 0f, dy: Float = 0f, k: Float = 1f) {
            val tx = 12f * (1f - k) + dx
            val ty = 11f * (1f - k) + dy
            dot(tx + 8.8f * k, ty + 8.8f * k, 3.4f * k, c)
            dot(tx + 14.4f * k, ty + 8.0f * k, 4.2f * k, c)
            bar(tx + 5.4f * k, ty + 9.9f * k, 13.2f * k, 4.4f * k, 2.2f * k, c)
        }

        fun crescent(cx: Float, cy: Float, r: Float, cutX: Float, cutY: Float, cutR: Float) {
            val full = Path().apply { addOval(Rect(p(cx, cy), r * s)) }
            val bite = Path().apply { addOval(Rect(p(cutX, cutY), cutR * s)) }
            drawPath(Path().apply { op(full, bite, PathOperation.Difference) }, Wx.Moon)
        }

        fun drops(n: Int, len: Float, c: Color) {
            val xs = if (n == 2) listOf(9.5f, 14.5f) else listOf(8f, 12f, 16f)
            xs.forEachIndexed { i, x ->
                val y0 = 15.8f + (i % 2) * 0.8f
                val l = len + if (i == 1) 1.2f else 0f
                stroke(x, y0, x, y0 + l, 2f, c)
            }
        }

        fun flakes() {
            listOf(8f to 17.6f, 12f to 20f, 16f to 17.6f).forEach { (x, y) ->
                dot(x, y, 1.35f, Wx.Snow)
            }
        }

        fun bolt() {
            val path = Path().apply {
                moveTo(p(13.6f, 14.6f).x, p(13.6f, 14.6f).y)
                lineTo(p(9.4f, 20.4f).x, p(9.4f, 20.4f).y)
                lineTo(p(11.9f, 20.4f).x, p(11.9f, 20.4f).y)
                lineTo(p(10.6f, 23.6f).x, p(10.6f, 23.6f).y)
                lineTo(p(15f, 17.8f).x, p(15f, 17.8f).y)
                lineTo(p(12.4f, 17.8f).x, p(12.4f, 17.8f).y)
                close()
            }
            drawPath(path, Wx.Bolt)
        }

        fun sunBig() {
            rays(12f, 12f, 7.2f, 9.8f, 2f)
            dot(12f, 12f, 4.6f, Wx.Sun)
        }

        fun sunSmall() {
            rays(8.4f, 7.6f, 4.6f, 6.4f, 1.7f)
            dot(8.4f, 7.6f, 3.1f, Wx.Sun)
        }

        when (sky) {
            Sky.CLEAR ->
                if (isDay) sunBig() else crescent(12f, 12f, 8.6f, 16.6f, 7.6f, 8.0f)

            Sky.MOSTLY_CLEAR -> {
                if (isDay) sunSmall() else crescent(8.4f, 7.6f, 5.6f, 11.5f, 4.4f, 5.2f)
                cloud(Wx.Cloud, 3.2f, 3.4f, 0.82f)
            }

            Sky.PARTLY -> {
                if (isDay) sunSmall() else crescent(8.4f, 7.6f, 5.6f, 11.5f, 4.4f, 5.2f)
                cloud(Wx.Cloud, 1.6f, 2.6f, 0.94f)
            }

            Sky.CLOUDY -> cloud(Wx.Cloud, 0f, 1.4f, 1f)

            Sky.OVERCAST -> {
                cloud(Wx.CloudDark, -1.8f, 0.4f, 0.86f)
                cloud(Wx.Cloud, 1.6f, 3.2f, 0.94f)
            }

            Sky.FOG -> {
                cloud(Wx.CloudDark, 0f, -0.6f, 0.92f)
                stroke(6.5f, 17.6f, 17.5f, 17.6f, 2f, Wx.Cloud)
                stroke(8.5f, 21f, 16.5f, 21f, 2f, Wx.Cloud)
            }

            Sky.DRIZZLE -> {
                cloud(Wx.Cloud)
                drops(2, 2.6f, Wx.Rain)
            }

            Sky.RAIN -> {
                cloud(Wx.Cloud)
                drops(3, 3.4f, Wx.Rain)
            }

            Sky.HEAVY_RAIN -> {
                cloud(Wx.CloudDark)
                drops(3, 5.6f, Wx.Rain)
            }

            Sky.SLEET -> {
                cloud(Wx.Cloud)
                stroke(9f, 15.8f, 9f, 19.2f, 2f, Wx.Rain)
                dot(14.6f, 18.4f, 1.35f, Wx.Snow)
            }

            Sky.SNOW -> {
                cloud(Wx.Cloud)
                flakes()
            }

            Sky.STORM -> {
                cloud(Wx.CloudDark)
                bolt()
            }
        }
    }
}
