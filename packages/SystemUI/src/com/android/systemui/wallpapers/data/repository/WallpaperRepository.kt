/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.wallpapers.data.repository

import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.utils.coroutines.flow.mapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** A repository storing information about the current wallpaper. */
interface WallpaperRepository {
    /** Emits the current user's current wallpaper. */
    val wallpaperInfo: StateFlow<WallpaperInfo?>

    /** Emits true if the current user's current wallpaper supports ambient mode. */
    val wallpaperSupportsAmbientMode: StateFlow<Boolean>
}

@SysUISingleton
class WallpaperRepositoryImpl
@Inject
constructor(
    @Background scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    broadcastDispatcher: BroadcastDispatcher,
    userRepository: UserRepository,
    private val wallpaperManager: WallpaperManager,
    context: Context,
) : WallpaperRepository {
    private val deviceSupportsAodWallpaper =
        context.resources.getBoolean(com.android.internal.R.bool.config_dozeSupportsAodWallpaper)

    private val wallpaperChanged: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(
                IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                user = UserHandle.ALL,
            )
            // The `combine` defining `wallpaperSupportsAmbientMode` will not run until both of the
            // input flows emit at least once. Since this flow is an input flow, it needs to emit
            // when it starts up to ensure that the `combine` will run if the user changes before we
            // receive a ACTION_WALLPAPER_CHANGED intent.
            // Note that the `selectedUser` flow does *not* need to emit on start because
            // [UserRepository.selectedUser] is a state flow which will automatically emit a value
            // on start.
            .onStart { emit(Unit) }

    private val selectedUser: Flow<SelectedUserModel> =
        userRepository.selectedUser
            // Only update the wallpaper status once the user selection has finished.
            .filter { it.selectionStatus == SelectionStatus.SELECTION_COMPLETE }

    override val wallpaperInfo: StateFlow<WallpaperInfo?> =
        if (!wallpaperManager.isWallpaperSupported || !deviceSupportsAodWallpaper) {
            MutableStateFlow(null).asStateFlow()
        } else {
            combine(wallpaperChanged, selectedUser, ::Pair)
                .mapLatestConflated { (_, selectedUser) -> getWallpaper(selectedUser) }
                .stateIn(
                    scope,
                    // Always be listening for wallpaper changes.
                    SharingStarted.Eagerly,
                    // The initial value is null, but it should get updated pretty quickly because
                    // the `combine` should immediately kick off a fetch.
                    initialValue = null,
                )
        }

    override val wallpaperSupportsAmbientMode: StateFlow<Boolean> =
        wallpaperInfo
            .map {
                // If WallpaperInfo is null, it's ImageWallpaper which never supports ambient mode.
                it?.supportsAmbientMode() == true
            }
            .stateIn(
                scope,
                // Always be listening for wallpaper changes.
                SharingStarted.Eagerly,
                initialValue = wallpaperInfo.value?.supportsAmbientMode() == true,
            )

    private suspend fun getWallpaper(selectedUser: SelectedUserModel): WallpaperInfo? {
        return withContext(bgDispatcher) {
            wallpaperManager.getWallpaperInfoForUser(selectedUser.userInfo.id)
        }
    }
}
