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
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.music.player.PlayerViewModel
import com.iris.music.ui.MainScreen
import com.iris.music.ui.theme.IRISMusicTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.reloadLibrary()
        }

    // 用户从「所有文件访问」设置页返回后重新建库
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    /** 沉浸式系统栏：内容延伸至状态栏/导航栏区域，系统栏透明、图标跟随主题，常驻显示 */
    private fun enableImmersiveMode() {
        // 内容延伸到状态栏/导航栏区域，背景铺满全屏（消除黑块）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 保持系统栏常驻（不隐藏），只做透明化处理，避免滑动唤出导致退应用要划两次
    }

    private fun requestNeededPermissions() {
        // targetSdk 30+：优先申请「所有文件访问」(MANAGE_EXTERNAL_STORAGE)。
        // 曲库直扫文件系统需要它才能用 File API 遍历共享存储；未授予时
        // 仅靠 READ_MEDIA_AUDIO 只能读进 MediaStore 的音频，会漏掉被系统
        // 误判成 video/image 的 mjpeg 封面歌。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                allFilesLauncher.launch(intent)
            }.onFailure {
                // 个别 ROM 不支持带包名的定向页，退回总列表页
                runCatching {
                    allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            return
        }

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.READ_MEDIA_AUDIO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // 只在确有未授予的权限时才弹窗。否则权限回调会触发 reloadLibrary()（完整扫盘），
        // 导致每次冷启动都强制重扫一遍——这正是"重新进应用又重新加载"的来源。
        // 权限齐全时什么都不做：ViewModel init 里的 loadLibrary() 已经走"缓存秒开"路径。
        val missing = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}