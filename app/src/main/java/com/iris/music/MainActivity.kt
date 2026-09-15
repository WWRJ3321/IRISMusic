/*
 * This file is part of IRIS Music.
 * Copyright (C) 2026 WWRJ
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.iris.music

import android.Manifest
import android.os.Build
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        enableImmersiveMode()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            IRISMusicTheme(
                theme = state.theme,
                mode = state.mode,
                cornerBase = state.cornerBase,
                surfaceStyle = state.surfaceStyle,
                glassBlur = state.glassBlur
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
                    onSelectFolder = { path -> if (path == null) viewModel.clearFolders() else viewModel.toggleFolder(path) },
                    onToggleLike = viewModel::toggleLike,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeat = viewModel::cycleRepeatMode,
                    onToggleSettings = viewModel::toggleSettings,
                    onThemeChange = viewModel::setTheme,
                    onModeChange = viewModel::setMode,
                    onRowSizeChange = viewModel::setRowSize,
                    onExplorationChange = viewModel::setExploration
                )
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
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.READ_MEDIA_AUDIO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}