package com.matt.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matt.weather.data.Place

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    recents: List<Place>,
    results: List<Place>,
    onQuery: (String) -> Unit,
    onPick: (Place) -> Unit,
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.Bg)
            .padding(horizontal = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onQuery(it) },
                placeholder = { Text("City, ZIP, or place", color = Wx.Fg3) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Wx.Fg,
                    unfocusedTextColor = Wx.Fg,
                    focusedContainerColor = Wx.Card,
                    unfocusedContainerColor = Wx.Card,
                    cursorColor = Wx.Accent,
                    focusedBorderColor = Wx.Accent,
                    unfocusedBorderColor = Wx.Line
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Done",
                color = Wx.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            )
        }

        if (recents.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp, start = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                recents.forEach { p ->
                    Text(
                        p.label,
                        color = Wx.Fg, fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Wx.Card)
                            .border(1.dp, Wx.Line, RoundedCornerShape(999.dp))
                            .clickable { onPick(p) }
                            .padding(horizontal = 13.dp, vertical = 8.dp)
                    )
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            items(results) { p ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(p) }
                        .padding(horizontal = 6.dp, vertical = 14.dp)
                ) {
                    Text(p.name, color = Wx.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val sub = listOf(p.region, p.country).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) {
                        Text(sub, color = Wx.Fg3, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.Line))
            }
        }
    }
}
