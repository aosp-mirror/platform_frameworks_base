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
import com.android.systemui.Flags
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.repository.KeyguardClockRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.shared.Flags.ambientAod
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import com.android.app.tracing.coroutines.launchTraced as launch
import kotlinx.coroutines.withContext

/** A repository storing information about the current wallpaper. */
interface WallpaperRepository {
    /** Emits the current user's current wallpaper. */
    val wallpaperInfo: StateFlow<WallpaperInfo?>

    /** Emits true if the current user's current wallpaper supports ambient mode. */
    val wallpaperSupportsAmbientMode: StateFlow<Boolean>

    /** Set rootView to get its windowToken afterwards */
    var rootView: View?
}

@SysUISingleton
class WallpaperRepositoryImpl
@Inject
constructor(
    @Background scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    broadcastDispatcher: BroadcastDispatcher,
    userRepository: UserRepository,
    keyguardRepository: KeyguardRepository,
    keyguardClockRepository: KeyguardClockRepository,
    private val wallpaperManager: WallpaperManager,
    context: Context,
) : WallpaperRepository {
    private val deviceSupportsAodWallpaper =
        context.resources.getBoolean(com.android.internal.R.bool.config_dozeSupportsAodWallpaper)

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

    /** The bottom of notification stack respect to the top of screen. */
    private val notificationStackAbsoluteBottom: StateFlow<Float> =
        keyguardRepository.notificationStackAbsoluteBottom

    /** The top of shortcut respect to the top of screen. */
    private val shortcutAbsoluteTop: StateFlow<Float> = keyguardRepository.shortcutAbsoluteTop

    /**
     * The top of notification stack to give a default state of lockscreen remaining space for
     * states with notifications to compare with. It's the bottom of smartspace date and weather
     * smartspace in small clock state, plus proper bottom margin.
     */
    private val notificationStackDefaultTop = keyguardClockRepository.notificationDefaultTop
    @VisibleForTesting var sendLockscreenLayoutJob: Job? = null
    private val lockscreenRemainingSpaceWithNotification: Flow<Triple<Float, Float, Float>> =
        combine(
            notificationStackAbsoluteBottom,
            notificationStackDefaultTop,
            shortcutAbsoluteTop,
            ::Triple,
        )

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
                if (ambientAod()) {
                    // Force this mode for now, until ImageWallpaper supports it directly
                    // TODO(b/371236225)
                    true
                } else {
                    // If WallpaperInfo is null, it's ImageWallpaper which never supports ambient
                    // mode.
                    it?.supportsAmbientMode() == true
                }
            }
            .stateIn(
                scope,
                // Always be listening for wallpaper changes.
                SharingStarted.Eagerly,
                initialValue = if (ambientAod()) true else false,
            )

    override var rootView: View? = null

    val shouldSendNotificationLayout =
        wallpaperInfo
            .map {
                val shouldSendNotificationLayout = shouldSendNotificationLayout(it)
                if (shouldSendNotificationLayout) {
                    sendLockscreenLayoutJob =
                        scope.launch {
                            lockscreenRemainingSpaceWithNotification.collect {
                                (notificationBottom, notificationDefaultTop, shortcutTop) ->
                                wallpaperManager.sendWallpaperCommand(
                                    /* windowToken = */ rootView?.windowToken,
                                    /* action = */ WallpaperManager
                                        .COMMAND_LOCKSCREEN_LAYOUT_CHANGED,
                                    /* x = */ 0,
                                    /* y = */ 0,
                                    /* z = */ 0,
                                    /* extras = */ Bundle().apply {
                                        putFloat("screenLeft", 0F)
                                        putFloat("smartspaceBottom", notificationDefaultTop)
                                        putFloat("notificationBottom", notificationBottom)
                                        putFloat(
                                            "screenRight",
                                            context.resources.displayMetrics.widthPixels.toFloat(),
                                        )
                                        putFloat("shortCutTop", shortcutTop)
                                    },
                                )
                            }
                        }
                } else {
                    sendLockscreenLayoutJob?.cancel()
                }
                shouldSendNotificationLayout
            }
            .stateIn(
                scope,
                // Always be listening for wallpaper changes.
                if (Flags.magicPortraitWallpapers()) SharingStarted.Eagerly
                else SharingStarted.Lazily,
                initialValue = false,
            )

    private suspend fun getWallpaper(selectedUser: SelectedUserModel): WallpaperInfo? {
        return withContext(bgDispatcher) {
            wallpaperManager.getWallpaperInfoForUser(selectedUser.userInfo.id)
        }
    }

    private fun shouldSendNotificationLayout(wallpaperInfo: WallpaperInfo?): Boolean {
        return if (wallpaperInfo != null && wallpaperInfo.component != null) {
            wallpaperInfo.component!!.className == MAGIC_PORTRAIT_CLASSNAME
        } else {
            false
        }
    }

    companion object {
        const val MAGIC_PORTRAIT_CLASSNAME =
            "com.google.android.apps.magicportrait.service.MagicPortraitWallpaperService"
    }
}
