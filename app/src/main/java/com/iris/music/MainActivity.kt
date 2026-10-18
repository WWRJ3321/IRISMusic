package com.iris.music

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.music.player.PlayerViewModel
import com.iris.music.ui.MainScreen
import com.iris.music.ui.SplashScreen
import com.iris.music.ui.theme.IRISMusicTheme
import com.iris.music.ui.theme.colors

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.reloadLibrary()
        }

    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.reloadLibrary()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        enableImmersiveMode()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = baseDensity.fontScale * state.fontScale
                )
            ) {
            IRISMusicTheme(
                theme = state.theme,
                mode = state.mode,
                cornerBase = state.cornerBase,
                surfaceStyle = state.surfaceStyle,
                glassBlur = state.glassBlur,
                uiFont = state.uiFont
            ) {
                var showSplash by remember { mutableStateOf(state.splashEnabled) }
                var startOnboarding by remember { mutableStateOf(!state.splashEnabled) }
                
                val colors = state.theme.colors(
                    state.mode, state.systemDark,
                    customPrimary = androidx.compose.ui.graphics.Color(state.customPrimaryArgb),
                    customSecondary = androidx.compose.ui.graphics.Color(state.customSecondaryArgb)
                )

                Box(Modifier.fillMaxSize()) {
                    MainScreen(
                        state = state,
                        viewModel = viewModel,
                        startOnboarding = startOnboarding,
                        onToggle = viewModel::togglePlay,
                        onPrev = viewModel::previous,
                        onNext = viewModel::next,
                        onSeek = viewModel::seekTo,
                        onSelect = viewModel::playAt,
                        onSearch = viewModel::updateSearch,
                        onToggleLike = viewModel::toggleLike,
                        onCyclePlayMode = viewModel::cyclePlayMode,
                        onToggleSettings = viewModel::toggleSettings
                    )
                    if (showSplash) {
                        SplashScreen(
                            style = state.splashStyle,
                            isDark = colors.isDark,
                            onFinished = { showSplash = false }
                        )
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(4100)
                            startOnboarding = true
                        }
                    }
                }
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
            if (!prefs.getBoolean("all_files_prompted", false)) {
                prefs.edit().putBoolean("all_files_prompted", true).apply()
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    allFilesLauncher.launch(intent)
                }.onFailure {
                    runCatching {
                        allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
        }

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.READ_MEDIA_AUDIO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
