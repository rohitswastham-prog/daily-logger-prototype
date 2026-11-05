package com.dailylog.prototype.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailylog.prototype.data.Field
import com.dailylog.prototype.data.Lang

private val SLOTS = listOf("breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "snack" to "Snack")

// ------------------------------------------------------------------ listening and processing

@Composable
fun ListeningScreen(seconds: Int, level: Float, onStop: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(C.Recording, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("Listening", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.Ink)
            Spacer(Modifier.width(8.dp))
            Text("%d:%02d".format(seconds / 60, seconds % 60), color = C.Muted)
        }
        Spacer(Modifier.height(40.dp))
        Waveform(level)
        Spacer(Modifier.height(32.dp))
        Text(
            "Say what you ate, your walk, sugar reading and how you felt.",
            color = C.Ink,
            fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(20.dp)).padding(18.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("English, हिंदी or मराठी — mixing is fine", color = C.Muted, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(88.dp).border(6.dp, C.Tint, CircleShape).padding(6.dp).clip(CircleShape).background(C.Primary)
                .clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(26.dp).background(Color.White, RoundedCornerShape(6.dp)))
        }
        Text("Tap to stop", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = onCancel) { Text("Cancel", color = C.Ink) }
        Text("Up to 3 minutes", color = C.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Waveform(level: Float) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "phase")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
        repeat(13) { i ->
            val shape = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin((i + phase * 3) * 0.7f))
            val height = (8 + 56 * shape * (0.25f + 0.75f * level.coerceIn(0f, 1f) * 3f).coerceAtMost(1f)).dp
            Box(Modifier.width(6.dp).height(height).background(C.Primary, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
fun ProcessingScreen(text: String, onCancel: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "dots")
    val step by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "step")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) { i ->
                val lit = step.toInt() == i
                Box(Modifier.size(20.dp).background(if (lit) C.Primary else C.Tint, CircleShape))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(text, color = C.Ink, fontSize = 17.sp)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel) { Text("Cancel", color = C.Muted) }
    }
}

// ------------------------------------------------------------------ photo

@Composable
fun PhotoReadyScreen(screen: Screen.PhotoReady, onSlot: (String) -> Unit, onRetake: () -> Unit, onSend: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Meal photo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Spacer(Modifier.height(12.dp))
        Image(
            screen.photo.preview.asImageBitmap(),
            contentDescription = "Your meal",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text("Which meal?", fontWeight = FontWeight.SemiBold, color = C.Ink)
        Spacer(Modifier.height(8.dp))
        SlotPills(screen.slot, onSlot)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp)) {
                Text("Retake", color = C.Ink)
            }
            Button(onClick = onSend, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp)) { Text("Send") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotPills(selected: String, onSlot: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SLOTS.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                color = if (on) Color.White else C.Ink,
                fontSize = 14.sp,
                modifier =
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) C.Primary else Color.White)
                        .border(1.dp, if (on) C.Primary else C.Border, RoundedCornerShape(20.dp))
                        .clickable { onSlot(key) }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmMealScreen(
    card: Screen.ConfirmMeal,
    lang: Lang,
    onSlot: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (String) -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var newItem by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    card.photo.preview.asImageBitmap(),
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("What's on the plate", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                    Text("Remove anything that's wrong, add what's missing.", color = C.Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            SlotPills(card.slot, onSlot)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.items.forEachIndexed { index, item ->
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(C.Tint).padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.display(), color = C.Ink, fontSize = 14.sp)
                        IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, "Remove ${item.name}", tint = C.Muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (!adding) {
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, C.Border, RoundedCornerShape(20.dp))
                            .clickable { adding = true }.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = C.Primary, modifier = Modifier.size(16.dp))
                        Text(" Add item", color = C.Primary, fontSize = 14.sp)
                    }
                }
            }
            if (adding) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newItem,
                    onValueChange = { newItem = it.take(60) },
                    placeholder = { Text("e.g. Curd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(onDone = {
                            onAdd(newItem)
                            newItem = ""
                            adding = false
                        }),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Only what's shown above is saved.", color = C.Muted, fontSize = 13.sp)
            EngineNote(card.entry.engine)
        }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp)) {
                Text("Retake", color = C.Ink)
            }
            Button(
                onClick = onSave,
                enabled = card.items.isNotEmpty(),
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(25.dp),
            ) { Text("Save") }
        }
    }
}

// ------------------------------------------------------------------ voice / text confirm card

@Composable
fun ConfirmLogScreen(
    card: Screen.ConfirmLog,
    lang: Lang,
    onEdit: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    var editing by remember { mutableIntStateOf(-1) }
    val entry = card.entry
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Here's what I'll save", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Spacer(Modifier.height(12.dp))
            entry.transcript?.let {
                Text("You said", color = C.Muted, fontSize = 12.sp)
                Text("“$it”", color = C.Muted, fontSize = 14.sp, fontStyle = FontStyle.Italic)
                Spacer(Modifier.height(12.dp))
            }
            if (entry.summary.isNotBlank() && card.fields.isNotEmpty()) {
                Text(
                    entry.summary,
                    color = C.Ink,
                    fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().background(C.Tint, RoundedCornerShape(16.dp)).padding(14.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            if (card.fields.isEmpty()) {
                Text(
                    "I couldn't find a meal, walk, sugar reading, weight or feeling in that. Go back and try again.",
                    color = C.Muted,
                    modifier = Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(16.dp)).padding(14.dp),
                )
            }
            card.fields.forEachIndexed { index, field ->
                FieldRow(
                    field = field,
                    lang = lang,
                    editing = editing == index,
                    onStartEdit = { editing = index },
                    onEdit = { onEdit(index, it) },
                    onDone = { editing = -1 },
                    onRemove = {
                        editing = -1
                        onRemove(index)
                    },
                )
            }
            if (entry.redFlags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Note(
                    icon = { Icon(Icons.Filled.Warning, null, tint = C.Danger, modifier = Modifier.size(18.dp)) },
                    text = "You mentioned: ${entry.redFlags.joinToString()}. If this is sudden or severe, contact your doctor now. Your care team will see this when you save.",
                    fill = C.DangerFill,
                    border = C.Danger.copy(alpha = 0.3f),
                    ink = C.Danger,
                )
            }
            entry.questions.forEach { question ->
                Spacer(Modifier.height(8.dp))
                Note(
                    icon = { Icon(Icons.Filled.Info, null, tint = C.NoteInk, modifier = Modifier.size(18.dp)) },
                    text = "You also asked “$question” — you can ask that in chat.",
                    fill = C.NoteFill,
                    border = C.NoteBorder,
                    ink = C.NoteInk,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Only what's shown above is saved.", color = C.Muted, fontSize = 13.sp)
            EngineNote(entry.engine)
        }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(25.dp)) {
                Text(if (entry.kind == "voice") "Re-record" else "Edit text", color = C.Ink)
            }
            Button(
                onClick = onSave,
                enabled = card.fields.isNotEmpty() && card.fields.all { it.value.isNotBlank() },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(25.dp),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun FieldRow(
    field: Field,
    lang: Lang,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onEdit: (String) -> Unit,
    onDone: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, C.Border, RoundedCornerShape(14.dp))
            .clickable(enabled = !editing, onClick = onStartEdit).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(field.label.pick(lang), color = C.Muted, fontSize = 13.sp)
                if (!editing) {
                    val extra = listOfNotNull(field.timeText(), field.context?.replace('_', ' ')).joinToString(" · ")
                    Text(field.display() + if (extra.isNotEmpty()) " · $extra" else "", color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (editing) {
                TextButton(onClick = onRemove) { Text("Remove", color = C.Danger) }
                TextButton(onClick = onDone) { Text("Done", color = C.Primary) }
            } else {
                IconButton(onClick = onStartEdit) { Icon(Icons.Filled.Edit, "Edit", tint = C.Ink, modifier = Modifier.size(20.dp)) }
            }
        }
        if (editing) {
            OutlinedTextField(
                value = field.value,
                onValueChange = onEdit,
                singleLine = true,
                suffix = field.unit?.let { { Text(it) } },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (field.numeric) KeyboardType.Decimal else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun Note(icon: @Composable () -> Unit, text: String, fill: Color, border: Color, ink: Color) {
    Row(
        Modifier.fillMaxWidth().background(fill, RoundedCornerShape(16.dp)).border(1.dp, border, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Box(Modifier.padding(top = 2.dp)) { icon() }
        Spacer(Modifier.width(10.dp))
        Text(text, color = ink, fontSize = 14.sp)
    }
}

@Composable
private fun EngineNote(engine: String?) {
    if (engine == "fake") {
        Spacer(Modifier.height(6.dp))
        Text("Sample card: the backend has no Groq key yet.", color = C.NoteInk, fontSize = 12.sp)
    }
}
