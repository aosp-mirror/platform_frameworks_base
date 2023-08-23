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
import android.view.accessibility.AccessibilityManager
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.res.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Business logic for use-cases related to the keyguard long-press feature. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardLongPressInteractor
@Inject
constructor(
    @Application private val appContext: Context,
    @Application private val scope: CoroutineScope,
    transitionInteractor: KeyguardTransitionInteractor,
    repository: KeyguardRepository,
    private val logger: UiEventLogger,
    private val featureFlags: FeatureFlags,
    broadcastDispatcher: BroadcastDispatcher,
    private val accessibilityManager: AccessibilityManagerWrapper,
) {
    /** Whether the long-press handling feature should be enabled. */
    val isLongPressHandlingEnabled: StateFlow<Boolean> =
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

    private val _isMenuVisible = MutableStateFlow(false)
    /** Model for whether the menu should be shown. */
    val isMenuVisible: StateFlow<Boolean> =
        isLongPressHandlingEnabled
            .flatMapLatest { isEnabled ->
                if (isEnabled) {
                    _isMenuVisible.asStateFlow()
                } else {
                    // Reset the state so we don't see a menu when long-press handling is enabled
                    // again in the future.
                    _isMenuVisible.value = false
                    flowOf(false)
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    private val _shouldOpenSettings = MutableStateFlow(false)
    /**
     * Whether the long-press accessible "settings" flow should be opened.
     *
     * Note that [onSettingsShown] must be invoked to consume this, once the settings are opened.
     */
    val shouldOpenSettings = _shouldOpenSettings.asStateFlow()

    private var delayedHideMenuJob: Job? = null

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
    fun onLongPress() {
        if (!isLongPressHandlingEnabled.value) {
            return
        }

        if (featureFlags.isEnabled(Flags.LOCK_SCREEN_LONG_PRESS_DIRECT_TO_WPP)) {
            showSettings()
        } else {
            showMenu()
        }
    }

    /** Notifies that the user has touched outside of the pop-up. */
    fun onTouchedOutside() {
        hideMenu()
    }

    /** Notifies that the user has started a touch gesture on the menu. */
    fun onMenuTouchGestureStarted() {
        cancelAutomaticMenuHiding()
    }

    /** Notifies that the user has started a touch gesture on the menu. */
    fun onMenuTouchGestureEnded(isClick: Boolean) {
        if (isClick) {
            hideMenu()
            logger.log(LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED)
            showSettings()
        } else {
            scheduleAutomaticMenuHiding()
        }
    }

    /** Notifies that the settings UI has been shown, consuming the event to show it. */
    fun onSettingsShown() {
        _shouldOpenSettings.value = false
    }

    private fun showSettings() {
        _shouldOpenSettings.value = true
    }

    private fun isFeatureEnabled(): Boolean {
        return featureFlags.isEnabled(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED) &&
            appContext.resources.getBoolean(R.bool.long_press_keyguard_customize_lockscreen_enabled)
    }

    /** Updates application state to ask to show the menu. */
    private fun showMenu() {
        _isMenuVisible.value = true
        scheduleAutomaticMenuHiding()
        logger.log(LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN)
    }

    private fun scheduleAutomaticMenuHiding() {
        cancelAutomaticMenuHiding()
        delayedHideMenuJob =
            scope.launch {
                delay(timeOutMs())
                hideMenu()
            }
    }

    /** Updates application state to ask to hide the menu. */
    private fun hideMenu() {
        cancelAutomaticMenuHiding()
        _isMenuVisible.value = false
    }

    private fun cancelAutomaticMenuHiding() {
        delayedHideMenuJob?.cancel()
        delayedHideMenuJob = null
    }

    private fun timeOutMs(): Long {
        return accessibilityManager
            .getRecommendedTimeoutMillis(
                DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS.toInt(),
                AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT,
            )
            .toLong()
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

    companion object {
        @VisibleForTesting const val DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS = 5000L
    }
}
