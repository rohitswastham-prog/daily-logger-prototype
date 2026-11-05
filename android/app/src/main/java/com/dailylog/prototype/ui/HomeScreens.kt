package com.dailylog.prototype.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailylog.prototype.R
import com.dailylog.prototype.data.Entry
import com.dailylog.prototype.data.Lang
import com.dailylog.prototype.data.StripDay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_LONG = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

@Composable
fun LoginScreen(signingIn: Boolean, onContinue: (String) -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    val valid = phone.filter(Char::isDigit).length == 10
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).background(C.Primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mic), null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Daily Log", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text("Say, type or photograph your day. You check it before anything is saved.", color = C.Muted, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() }.take(10) },
            label = { Text("Mobile number") },
            prefix = { Text("+91 ") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid && !signingIn) onContinue(phone) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onContinue(phone) },
            enabled = valid && !signingIn,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            if (signingIn) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Continue")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Prototype: a new number registers you, a known number signs you in. No OTP. Use test data only.",
            color = C.Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun HomeScreen(
    state: UiState,
    onLang: (Lang) -> Unit,
    onDate: (LocalDate) -> Unit,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onTyped: (String) -> Unit,
    onSend: () -> Unit,
    onLogout: () -> Unit,
) {
    val today = LocalDate.now()
    val isToday = state.date == today
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Daily Log", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                    Text(state.phone.orEmpty(), fontSize = 13.sp, color = C.Muted)
                }
                LangPill(state.lang, onLang)
                IconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Sign out", tint = C.Muted) }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Text(state.date.format(MONTH), fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.weight(1f))
                Text("Last 7 days", color = C.Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            DateStrip(strip = state.day?.strip.orEmpty(), selected = state.date, onDate = onDate)
            if (!isToday) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().background(C.NoteFill, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Logging for ${state.date.format(DAY_LONG)}", color = C.NoteInk, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Text("Back to today", color = C.NoteInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.clickable { onDate(today) })
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(C.Primary).clickable(onClick = onMic),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_mic), "Tap to speak", tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("Tap to speak", color = C.Ink, fontWeight = FontWeight.SemiBold)
                Text("Meals, walk, sugar, how you felt", color = C.Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("Add a meal photo", fontWeight = FontWeight.SemiBold, color = C.Ink)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhotoButton("Camera", R.drawable.ic_camera, onCamera, Modifier.weight(1f))
                PhotoButton("From gallery", R.drawable.ic_gallery, onGallery, Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))
            Text(if (isToday) "Logged today" else "Logged for ${state.date.format(DAY_LONG)}", fontWeight = FontWeight.SemiBold, color = C.Ink)
            Spacer(Modifier.height(8.dp))
            val entries = state.day?.takeIf { it.date == state.date }?.entries.orEmpty()
            when {
                state.dayLoading && entries.isEmpty() -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                entries.isEmpty() -> Text("Nothing logged for this day yet.", color = C.Muted, fontSize = 14.sp)
                else -> entries.forEach { LoggedRow(it, state.lang) }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.typed,
                onValueChange = onTyped,
                placeholder = { Text("Or type instead…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            IconButton(onClick = onSend, enabled = state.typed.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (state.typed.isNotBlank()) C.Primary else C.Border)
            }
        }
    }
}

@Composable
private fun LangPill(lang: Lang, onLang: (Lang) -> Unit) {
    val next = Lang.entries[(lang.ordinal + 1) % Lang.entries.size]
    Text(
        lang.pill,
        modifier =
            Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, C.Border, RoundedCornerShape(16.dp))
                .clickable { onLang(next) }.padding(horizontal = 12.dp, vertical = 6.dp),
        fontSize = 13.sp,
        color = C.Ink,
    )
}

@Composable
private fun DateStrip(strip: List<StripDay>, selected: LocalDate, onDate: (LocalDate) -> Unit) {
    val days = strip.ifEmpty { (6 downTo 0).map { StripDay(LocalDate.now().minusDays(it.toLong()), "none", it == 0) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        days.forEach { day ->
            val isSelected = day.date == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) C.Primary else C.Surface)
                    .clickable { onDate(day.date) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ink = if (isSelected) Color.White else C.Ink
                Text(
                    if (day.isToday) "Today" else day.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase),
                    fontSize = 11.sp,
                    color = if (isSelected) Color.White else C.Muted,
                    maxLines = 1,
                )
                Text(day.date.dayOfMonth.toString(), fontWeight = FontWeight.SemiBold, color = ink)
                Spacer(Modifier.height(4.dp))
                StatusDot(day.status, isSelected)
            }
        }
    }
}

@Composable
private fun StatusDot(status: String, onDark: Boolean) {
    val color = if (onDark) Color.White else C.Primary
    val modifier = Modifier.size(8.dp).clip(CircleShape)
    when (status) {
        "full" -> Box(modifier.background(color))
        "half" -> Box(modifier.border(1.5.dp, color, CircleShape)) { Box(Modifier.size(4.dp, 8.dp).background(color)) }
        else -> Box(modifier.border(1.dp, if (onDark) Color.White else C.Border, CircleShape))
    }
}

@Composable
private fun PhotoButton(text: String, icon: Int, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, C.Border),
    ) {
        Icon(painterResource(icon), null, tint = C.Ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = C.Ink, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun LoggedRow(entry: Entry, lang: Lang) {
    val kind =
        when (entry.kind) {
            "voice" -> "Voice"
            "photo" -> "Photo"
            else -> "Typed"
        }
    val time = entry.confirmedAt?.atZone(ZoneId.systemDefault())?.format(TIME).orEmpty()
    val line =
        if (entry.kind == "photo") {
            "${entry.slotLabel?.pick(lang) ?: "Meal"}: ${entry.items.joinToString(", ") { it.display() }}"
        } else {
            entry.fields.joinToString(" · ") { "${it.label.pick(lang)} ${it.display()}" }
        }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, C.Border, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Text(line, color = C.Ink, fontSize = 14.sp)
        Text(
            "$kind · $time" + if (entry.loggedLater) " · logged later" else "",
            color = C.Muted,
            fontSize = 12.sp,
        )
        if (entry.redFlags.isNotEmpty()) {
            Text("Flagged: ${entry.redFlags.joinToString()}", color = C.Danger, fontSize = 12.sp)
        }
    }
}
