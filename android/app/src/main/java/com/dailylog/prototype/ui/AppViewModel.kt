package com.dailylog.prototype.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailylog.prototype.BuildConfig
import com.dailylog.prototype.data.Api
import com.dailylog.prototype.data.ApiException
import com.dailylog.prototype.data.DayView
import com.dailylog.prototype.data.Entry
import com.dailylog.prototype.data.Field
import com.dailylog.prototype.data.Lang
import com.dailylog.prototype.data.MealItem
import com.dailylog.prototype.data.Session
import com.dailylog.prototype.media.PhotoPrep
import com.dailylog.prototype.media.PreparedPhoto
import com.dailylog.prototype.media.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

sealed interface Screen {
    data object Login : Screen

    data object Home : Screen

    data object Listening : Screen

    data class Processing(val text: String) : Screen

    data class PhotoReady(val photo: PreparedPhoto, val slot: String) : Screen

    data class ConfirmLog(val entry: Entry, val fields: List<Field>) : Screen

    data class ConfirmMeal(val entry: Entry, val photo: PreparedPhoto, val items: List<MealItem>, val slot: String) : Screen
}

data class UiState(
    val screen: Screen,
    val phone: String?,
    val date: LocalDate = LocalDate.now(),
    val day: DayView? = null,
    val dayLoading: Boolean = false,
    val lang: Lang = Lang.EN,
    val signingIn: Boolean = false,
    val typed: String = "",
    val seconds: Int = 0,
    val level: Float = 0f,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val session = Session(app)
    private val api = Api(BuildConfig.BACKEND_URL, session)
    private val recorder = Recorder(app)

    private val _state = MutableStateFlow(UiState(screen = if (session.token == null) Screen.Login else Screen.Home, phone = session.phone))
    val state: StateFlow<UiState> = _state

    private var work: Job? = null
    private var ticker: Job? = null
    private var lastTyped: String? = null

    init {
        if (session.token != null) loadDay()
    }

    // ---------------------------------------------------------------- sign in

    fun login(phone: String) {
        _state.update { it.copy(signingIn = true) }
        viewModelScope.launch {
            try {
                val result = api.login(phone)
                session.save(result.token, result.phone)
                _state.update {
                    it.copy(
                        screen = Screen.Home,
                        phone = result.phone,
                        signingIn = false,
                        message = if (result.isNew) "Welcome! Your number is registered." else "Welcome back.",
                    )
                }
                loadDay()
            } catch (error: ApiException) {
                _state.update { it.copy(signingIn = false, message = error.message) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            api.logout()
            session.clear()
            _state.value = UiState(screen = Screen.Login, phone = null)
        }
    }

    // ---------------------------------------------------------------- home

    fun setLang(lang: Lang) = _state.update { it.copy(lang = lang) }

    fun pickDate(date: LocalDate) {
        _state.update { it.copy(date = date) }
        loadDay()
    }

    fun typed(text: String) = _state.update { it.copy(typed = text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun loadDay() {
        val date = _state.value.date
        _state.update { it.copy(dayLoading = true) }
        viewModelScope.launch {
            try {
                val day = api.day(date)
                _state.update { if (it.date == date) it.copy(day = day, dayLoading = false) else it }
            } catch (error: ApiException) {
                fail(error, Screen.Home)
                _state.update { it.copy(dayLoading = false) }
            }
        }
    }

    // ---------------------------------------------------------------- voice

    fun startListening() {
        try {
            recorder.start()
        } catch (error: Exception) {
            _state.update { it.copy(message = "Couldn't start the microphone.") }
            return
        }
        _state.update { it.copy(screen = Screen.Listening, seconds = 0, level = 0f) }
        val started = System.currentTimeMillis()
        ticker =
            viewModelScope.launch {
                while (isActive) {
                    val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
                    _state.update { it.copy(seconds = seconds, level = recorder.level()) }
                    if (seconds >= MAX_SECONDS) {
                        stopListening()
                        break
                    }
                    delay(100)
                }
            }
    }

    fun stopListening() {
        ticker?.cancel()
        val file = recorder.stop()
        if (file == null) {
            _state.update { it.copy(screen = Screen.Home, message = "Couldn't hear that. Try again or type it.") }
            return
        }
        val date = _state.value.date
        run(Screen.Processing(PROCESSING_LOG), back = Screen.Home) {
            try {
                val entry = api.submitVoice(UUID.randomUUID().toString(), date, file)
                lastTyped = null
                Screen.ConfirmLog(entry, entry.fields)
            } finally {
                file.delete() // audio never stays on the phone
            }
        }
    }

    fun cancelListening() {
        ticker?.cancel()
        recorder.cancel()
        _state.update { it.copy(screen = Screen.Home) }
    }

    // ---------------------------------------------------------------- text

    fun sendText() {
        val text = _state.value.typed.trim()
        if (text.isEmpty()) return
        val date = _state.value.date
        _state.update { it.copy(typed = "") }
        run(Screen.Processing(PROCESSING_LOG), back = Screen.Home, restoreText = text) {
            val entry = api.submitText(UUID.randomUUID().toString(), date, text)
            lastTyped = text
            Screen.ConfirmLog(entry, entry.fields)
        }
    }

    // ---------------------------------------------------------------- photo

    fun photoPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val photo = withContext(Dispatchers.Default) { runCatching { PhotoPrep.prepare(getApplication(), uri) }.getOrNull() }
            if (photo == null) {
                _state.update { it.copy(message = "Couldn't open that photo.") }
            } else {
                _state.update { it.copy(screen = Screen.PhotoReady(photo, slotNow())) }
            }
        }
    }

    fun pickSlot(slot: String) =
        _state.update {
            when (val s = it.screen) {
                is Screen.PhotoReady -> it.copy(screen = s.copy(slot = slot))
                is Screen.ConfirmMeal -> it.copy(screen = s.copy(slot = slot))
                else -> it
            }
        }

    fun sendPhoto() {
        val ready = _state.value.screen as? Screen.PhotoReady ?: return
        val date = _state.value.date
        run(Screen.Processing(PROCESSING_PHOTO), back = ready) {
            val entry = api.submitPhoto(UUID.randomUUID().toString(), date, ready.slot, ready.photo.jpeg)
            Screen.ConfirmMeal(entry, ready.photo, entry.items, entry.slot ?: ready.slot)
        }
    }

    fun removeItem(index: Int) = updateMeal { it.copy(items = it.items.filterIndexed { i, _ -> i != index }) }

    fun addItem(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) updateMeal { it.copy(items = it.items + MealItem(clean.replaceFirstChar(Char::uppercase), "other", null)) }
    }

    private fun updateMeal(change: (Screen.ConfirmMeal) -> Screen.ConfirmMeal) =
        _state.update { s -> (s.screen as? Screen.ConfirmMeal)?.let { s.copy(screen = change(it)) } ?: s }

    // ---------------------------------------------------------------- confirm card

    fun editField(index: Int, value: String) =
        _state.update { s ->
            val card = s.screen as? Screen.ConfirmLog ?: return@update s
            s.copy(screen = card.copy(fields = card.fields.mapIndexed { i, f -> if (i == index) f.copy(value = value) else f }))
        }

    fun removeField(index: Int) =
        _state.update { s ->
            val card = s.screen as? Screen.ConfirmLog ?: return@update s
            s.copy(screen = card.copy(fields = card.fields.filterIndexed { i, _ -> i != index }))
        }

    fun save() {
        val current = _state.value.screen
        val date = _state.value.date
        when (current) {
            is Screen.ConfirmLog ->
                run(Screen.Processing("Saving…"), back = current) {
                    api.confirmLog(current.entry.id, current.fields)
                    saved(date)
                }
            is Screen.ConfirmMeal ->
                run(Screen.Processing("Saving…"), back = current) {
                    api.confirmMeal(current.entry.id, current.slot, current.items)
                    saved(date)
                }
            else -> Unit
        }
    }

    private fun saved(date: LocalDate): Screen {
        lastTyped = null
        val today = LocalDate.now()
        val where = if (date == today) "today's log" else date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH))
        _state.update { it.copy(message = "Saved to $where") }
        loadDay()
        return Screen.Home
    }

    /** Back walks one screen: a card goes back to where it came from, and its draft is discarded. */
    fun back(): Boolean {
        val s = _state.value
        when (val screen = s.screen) {
            Screen.Login, Screen.Home -> return false
            Screen.Listening -> cancelListening()
            is Screen.Processing -> {
                work?.cancel()
                _state.update { it.copy(screen = Screen.Home) }
            }
            is Screen.PhotoReady -> _state.update { it.copy(screen = Screen.Home) }
            is Screen.ConfirmLog -> {
                discardLater(screen.entry.id)
                _state.update { it.copy(screen = Screen.Home, typed = lastTyped ?: it.typed) }
                lastTyped = null
            }
            is Screen.ConfirmMeal -> {
                discardLater(screen.entry.id)
                _state.update { it.copy(screen = Screen.PhotoReady(screen.photo, screen.slot)) }
            }
        }
        return true
    }

    private fun discardLater(id: String) {
        viewModelScope.launch { api.discard(id) }
    }

    private fun run(
        processing: Screen,
        back: Screen,
        restoreText: String? = null,
        block: suspend () -> Screen,
    ) {
        work?.cancel()
        _state.update { it.copy(screen = processing) }
        work =
            viewModelScope.launch {
                try {
                    val next = block()
                    _state.update { it.copy(screen = next) }
                } catch (error: ApiException) {
                    fail(error, back)
                    if (restoreText != null) _state.update { it.copy(typed = restoreText) }
                }
            }
    }

    private fun fail(error: ApiException, back: Screen) {
        if (session.token == null) {
            _state.value = UiState(screen = Screen.Login, phone = null, message = error.message)
        } else {
            _state.update { it.copy(screen = back, message = error.message) }
        }
    }

    private fun slotNow(): String {
        val hour = LocalTime.now().hour
        return when (hour) {
            in 5..10 -> "breakfast"
            in 11..15 -> "lunch"
            in 16..18 -> "snack"
            else -> "dinner"
        }
    }

    override fun onCleared() {
        recorder.cancel()
    }

    companion object {
        const val MAX_SECONDS = 180
        const val PROCESSING_LOG = "Picking out your meals, walk and readings…"
        const val PROCESSING_PHOTO = "Looking at your plate…"
    }
}
