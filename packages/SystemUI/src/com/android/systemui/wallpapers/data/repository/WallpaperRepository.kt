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
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R as SysUIR
import com.android.systemui.shared.Flags.ambientAod
import com.android.systemui.shared.Flags.extendedWallpaperEffects
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.utils.coroutines.flow.mapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** A repository storing information about the current wallpaper. */
interface WallpaperRepository {
    /** Emits the current user's current wallpaper. */
    val wallpaperInfo: StateFlow<WallpaperInfo?>

    /** Emits true if the current user's current wallpaper supports ambient mode. */
    val wallpaperSupportsAmbientMode: Flow<Boolean>

    /** Set rootView to get its windowToken afterwards */
    var rootView: View?

    /** some wallpapers require bounds to be sent from keyguard */
    val shouldSendFocalArea: StateFlow<Boolean>
}

@SysUISingleton
class WallpaperRepositoryImpl
@Inject
constructor(
    @Background scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    broadcastDispatcher: BroadcastDispatcher,
    userRepository: UserRepository,
    wallpaperFocalAreaRepository: WallpaperFocalAreaRepository,
    private val wallpaperManager: WallpaperManager,
    private val context: Context,
) : WallpaperRepository {
    private val wallpaperChanged: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), user = UserHandle.ALL)
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

    @VisibleForTesting var sendLockscreenLayoutJob: Job? = null
    @VisibleForTesting var sendTapInShapeEffectsJob: Job? = null

    override val wallpaperInfo: StateFlow<WallpaperInfo?> =
        if (!wallpaperManager.isWallpaperSupported) {
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

    override val wallpaperSupportsAmbientMode: Flow<Boolean> =
        flowOf(context.resources.getBoolean(R.bool.config_dozeSupportsAodWallpaper) && ambientAod())

    override var rootView: View? = null

    override val shouldSendFocalArea =
        wallpaperInfo
            .map {
                val focalAreaTarget = context.resources.getString(SysUIR.string.focal_area_target)
                val shouldSendNotificationLayout = it?.component?.className == focalAreaTarget
                if (shouldSendNotificationLayout) {
                    sendLockscreenLayoutJob =
                        scope.launch {
                            wallpaperFocalAreaRepository.wallpaperFocalAreaBounds.collect {
                                wallpaperFocalAreaBounds ->
                                wallpaperManager.sendWallpaperCommand(
                                    /* windowToken = */ rootView?.windowToken,
                                    /* action = */ WallpaperManager
                                        .COMMAND_LOCKSCREEN_LAYOUT_CHANGED,
                                    /* x = */ 0,
                                    /* y = */ 0,
                                    /* z = */ 0,
                                    /* extras = */ Bundle().apply {
                                        putFloat(
                                            "wallpaperFocalAreaLeft",
                                            wallpaperFocalAreaBounds.left,
                                        )
                                        putFloat(
                                            "wallpaperFocalAreaRight",
                                            wallpaperFocalAreaBounds.right,
                                        )
                                        putFloat(
                                            "wallpaperFocalAreaTop",
                                            wallpaperFocalAreaBounds.top,
                                        )
                                        putFloat(
                                            "wallpaperFocalAreaBottom",
                                            wallpaperFocalAreaBounds.bottom,
                                        )
                                    },
                                )
                            }
                        }

                    sendTapInShapeEffectsJob =
                        scope.launch {
                            wallpaperFocalAreaRepository.wallpaperFocalAreaTapPosition.collect {
                                wallpaperFocalAreaTapPosition ->
                                wallpaperManager.sendWallpaperCommand(
                                    /* windowToken = */ rootView?.windowToken,
                                    /* action = */ WallpaperManager.COMMAND_LOCKSCREEN_TAP,
                                    /* x = */ wallpaperFocalAreaTapPosition.x.toInt(),
                                    /* y = */ wallpaperFocalAreaTapPosition.y.toInt(),
                                    /* z = */ 0,
                                    /* extras = */ null,
                                )
                            }
                        }
                } else {
                    sendLockscreenLayoutJob?.cancel()
                    sendTapInShapeEffectsJob?.cancel()
                }
                shouldSendNotificationLayout
            }
            .stateIn(
                scope,
                if (extendedWallpaperEffects()) SharingStarted.Eagerly else WhileSubscribed(),
                initialValue = extendedWallpaperEffects(),
            )

    private suspend fun getWallpaper(selectedUser: SelectedUserModel): WallpaperInfo? {
        return withContext(bgDispatcher) {
            wallpaperManager.getWallpaperInfoForUser(selectedUser.userInfo.id)
        }
    }
}
