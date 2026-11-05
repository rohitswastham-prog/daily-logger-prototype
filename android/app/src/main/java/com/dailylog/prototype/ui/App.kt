package com.dailylog.prototype.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    BackHandler(enabled = state.screen != Screen.Login && state.screen != Screen.Home) { vm.back() }

    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.startListening()
        }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            if (taken) vm.photoPicked(cameraUri?.let(Uri::parse))
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.photoPicked(uri) }

    val onMic = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val onCamera = {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "meal.jpg"))
        cameraUri = uri.toString()
        camera.launch(uri)
    }
    val onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).safeDrawingPadding().imePadding()) {
            when (val screen = state.screen) {
                Screen.Login -> LoginScreen(signingIn = state.signingIn, onContinue = vm::login)
                Screen.Home ->
                    HomeScreen(
                        state = state,
                        onLang = vm::setLang,
                        onDate = vm::pickDate,
                        onMic = onMic,
                        onCamera = onCamera,
                        onGallery = onGallery,
                        onTyped = vm::typed,
                        onSend = vm::sendText,
                        onLogout = vm::logout,
                    )
                Screen.Listening ->
                    ListeningScreen(seconds = state.seconds, level = state.level, onStop = vm::stopListening, onCancel = vm::cancelListening)
                is Screen.Processing -> ProcessingScreen(screen.text, onCancel = { vm.back() })
                is Screen.PhotoReady ->
                    PhotoReadyScreen(screen, onSlot = vm::pickSlot, onRetake = { vm.back() }, onSend = vm::sendPhoto)
                is Screen.ConfirmLog ->
                    ConfirmLogScreen(
                        card = screen,
                        lang = state.lang,
                        onEdit = vm::editField,
                        onRemove = vm::removeField,
                        onBack = { vm.back() },
                        onSave = vm::save,
                    )
                is Screen.ConfirmMeal ->
                    ConfirmMealScreen(
                        card = screen,
                        lang = state.lang,
                        onSlot = vm::pickSlot,
                        onRemove = vm::removeItem,
                        onAdd = vm::addItem,
                        onRetake = { vm.back() },
                        onSave = vm::save,
                    )
            }
        }
    }
}
