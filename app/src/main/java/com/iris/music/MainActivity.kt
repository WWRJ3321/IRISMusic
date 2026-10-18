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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.music.player.PlayerViewModel
import com.iris.music.ui.MainScreen
import com.iris.music.ui.SplashScreen
import com.iris.music.ui.theme.IRISMusicTheme

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
                // 控制 Splash 是否还盖在最上面
                var showSplash by remember { mutableStateOf(state.splashEnabled) }
                // 控制主界面的“呼吸感”状态：Splash 结束前不显示，结束后浮现
                var isMainVisible by remember { mutableStateOf(!state.splashEnabled) }

                // 主界面浮现动画：放大 + 透明度恢复
                val mainAlpha by animateFloatAsState(
                    targetValue = if (isMainVisible) 1f else 0f,
                    animationSpec = tween(durationMillis = 1000),
                    label = "mainAlpha"
                )
                val mainScale by animateFloatAsState(
                    targetValue = if (isMainVisible) 1f else 0.94f,
                    animationSpec = tween(durationMillis = 1000),
                    label = "mainScale"
                )

                Box(Modifier.fillMaxSize()) {
                    // 底层主界面
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = mainAlpha
                                scaleX = mainScale
                                scaleY = mainScale
                            }
                    ) {
                        MainScreen(
                            state = state,
                            viewModel = viewModel,
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
                    }

                    // 顶层启动页
                    if (showSplash) {
                        SplashScreen(
                            style = state.splashStyle,
                            onFinished = {
                                // 动画完全播完（背景已透明），才移除 Splash 层
                                showSplash = false
                            }
                        )
                        // 当动画开始进入退出阶段时，让底下的 MainScreen 提前开始浮现
                        // 使用 LaunchedEffect 监听 Splash 的进度，或者简单点，在 splash 准备结束时提前触发
                        // 我们利用 state.splashEnabled 已经保证进来的逻辑，在这里直接用一个延迟触发主界面的淡入
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(4400) // 4秒书写 + 400ms 停留
                            isMainVisible = true
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
