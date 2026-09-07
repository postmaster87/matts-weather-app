package com.matt.weather.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Same palette as the web build — high contrast, tuned for outdoor daylight. */
object Wx {
    val Bg = Color(0xFF0E141B)
    val Card = Color(0xFF171F29)
    val Card2 = Color(0xFF1D2733)
    val Track = Color(0xFF202B38)
    val Line = Color(0xFF2A3543)
    val Fg = Color(0xFFEEF4FB)
    val Fg2 = Color(0xFFA7B6C7)
    val Fg3 = Color(0xFF72829A)
    val Accent = Color(0xFF57A9FF)
    val Warm = Color(0xFFFFB340)
    val Cold = Color(0xFF7CC8FF)
    val Wet = Color(0xFF3D8BFF)

    val AlertBg = Color(0xFF3A1B19)
    val AlertLine = Color(0xFF6B2B25)
    val AlertBar = Color(0xFFFF5F52)
    val AlertFg = Color(0xFFFFD3CE)
    val AlertFg2 = Color(0xFFE4B7B2)

    val Sun = Color(0xFFFFC44D)
    val Moon = Color(0xFFCFD9E6)
    val Cloud = Color(0xFFB6C4D4)
    val CloudDark = Color(0xFF8D9DB1)
    val Rain = Color(0xFF5FA8FF)
    val Snow = Color(0xFFDCEBF7)
    val Bolt = Color(0xFFFFD24D)
}

@Composable
fun WeatherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Wx.Accent,
            background = Wx.Bg,
            surface = Wx.Card,
            surfaceVariant = Wx.Card2,
            onPrimary = Wx.Bg,
            onBackground = Wx.Fg,
            onSurface = Wx.Fg,
            onSurfaceVariant = Wx.Fg2,
            outline = Wx.Line
        ),
        typography = Typography(),
        content = content
    )
}
