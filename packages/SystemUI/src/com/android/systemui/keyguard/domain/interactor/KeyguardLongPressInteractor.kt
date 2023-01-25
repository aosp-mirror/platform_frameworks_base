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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.shared.model.Position
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.domain.model.KeyguardSettingsPopupMenuModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Business logic for use-cases related to the keyguard long-press feature. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardLongPressInteractor
@Inject
constructor(
    @Application unsafeContext: Context,
    @Application scope: CoroutineScope,
    transitionInteractor: KeyguardTransitionInteractor,
    repository: KeyguardRepository,
    private val activityStarter: ActivityStarter,
    private val logger: UiEventLogger,
    private val featureFlags: FeatureFlags,
    broadcastDispatcher: BroadcastDispatcher,
) {
    private val appContext = unsafeContext.applicationContext

    private val _isLongPressHandlingEnabled: StateFlow<Boolean> =
        if (isFeatureEnabled()) {
                combine(
                    transitionInteractor.finishedKeyguardState.map {
                        it == KeyguardState.LOCKSCREEN
                    },
                    repository.isQuickSettingsVisible,
                ) { isFullyTransitionedToLockScreen, isQuickSettingsVisible ->
                    isFullyTransitionedToLockScreen && !isQuickSettingsVisible
                }
            } else {
                flowOf(false)
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Whether the long-press handling feature should be enabled. */
    val isLongPressHandlingEnabled: Flow<Boolean> = _isLongPressHandlingEnabled

    private val _menu = MutableStateFlow<KeyguardSettingsPopupMenuModel?>(null)
    /** Model for a menu that should be shown; `null` when no menu should be shown. */
    val menu: Flow<KeyguardSettingsPopupMenuModel?> =
        isLongPressHandlingEnabled.flatMapLatest { isEnabled ->
            if (isEnabled) {
                _menu
            } else {
                flowOf(null)
            }
        }

    init {
        if (isFeatureEnabled()) {
            broadcastDispatcher
                .broadcastFlow(
                    IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                )
                .onEach { hideMenu() }
                .launchIn(scope)
        }
    }

    /** Notifies that the user has long-pressed on the lock screen. */
    fun onLongPress(x: Int, y: Int) {
        if (!_isLongPressHandlingEnabled.value) {
            return
        }

        showMenu(
            x = x,
            y = y,
        )
    }

    private fun isFeatureEnabled(): Boolean {
        return featureFlags.isEnabled(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED) &&
            featureFlags.isEnabled(Flags.REVAMPED_WALLPAPER_UI)
    }

    /** Updates application state to ask to show the menu at the given coordinates. */
    private fun showMenu(
        x: Int,
        y: Int,
    ) {
        _menu.value =
            KeyguardSettingsPopupMenuModel(
                position =
                    Position(
                        x = x,
                        y = y,
                    ),
                onClicked = {
                    hideMenu()
                    navigateToLockScreenSettings()
                },
                onDismissed = { hideMenu() },
            )
        logger.log(LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN)
    }

    /** Updates application state to ask to hide the menu. */
    private fun hideMenu() {
        _menu.value = null
    }

    /** Opens the wallpaper picker screen after the device is unlocked by the user. */
    private fun navigateToLockScreenSettings() {
        logger.log(LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED)
        activityStarter.dismissKeyguardThenExecute(
            /* action= */ {
                appContext.startActivity(
                    Intent(Intent.ACTION_SET_WALLPAPER).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        appContext
                            .getString(R.string.config_wallpaperPickerPackage)
                            .takeIf { it.isNotEmpty() }
                            ?.let { packageName -> setPackage(packageName) }
                    }
                )
                true
            },
            /* cancel= */ {},
            /* afterKeyguardGone= */ true,
        )
    }

    enum class LogEvents(
        private val _id: Int,
    ) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The lock screen was long-pressed and we showed the settings popup menu.")
        LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN(1292),
        @UiEvent(doc = "The lock screen long-press popup menu was clicked.")
        LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED(1293),
        ;

        override fun getId() = _id
    }
}
