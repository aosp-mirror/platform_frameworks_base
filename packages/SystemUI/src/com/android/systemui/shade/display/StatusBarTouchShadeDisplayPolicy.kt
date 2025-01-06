/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shade.display

import android.util.Log
import android.view.Display
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.shade.ShadeOnDefaultDisplayWhenLocked
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Moves the shade on the last display that received a status bar touch.
 *
 * If the display is removed, falls back to the default one. When [shadeOnDefaultDisplayWhenLocked]
 * is true, the shade falls back to the default display when the keyguard is visible.
 */
@SysUISingleton
class StatusBarTouchShadeDisplayPolicy
@Inject
constructor(
    displayRepository: DisplayRepository,
    keyguardRepository: KeyguardRepository,
    @Background val backgroundScope: CoroutineScope,
    @ShadeOnDefaultDisplayWhenLocked val shadeOnDefaultDisplayWhenLocked: Boolean,
) : ShadeDisplayPolicy {
    override val name: String = "status_bar_latest_touch"

    private val currentDisplayId = MutableStateFlow(Display.DEFAULT_DISPLAY)
    private val availableDisplayIds: StateFlow<Set<Int>> = displayRepository.displayIds

    override val displayId: StateFlow<Int> =
        if (shadeOnDefaultDisplayWhenLocked) {
            keyguardRepository.isKeyguardShowing
                .combine(currentDisplayId) { isKeyguardShowing, currentDisplayId ->
                    if (isKeyguardShowing) {
                        Display.DEFAULT_DISPLAY
                    } else {
                        currentDisplayId
                    }
                }
                .stateIn(backgroundScope, SharingStarted.WhileSubscribed(), currentDisplayId.value)
        } else {
            currentDisplayId
        }

    private var removalListener: Job? = null

    /** Called when the status bar on the given display is touched. */
    fun onStatusBarTouched(statusBarDisplayId: Int) {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        if (statusBarDisplayId !in availableDisplayIds.value) {
            Log.e(TAG, "Got touch on unknown display $statusBarDisplayId")
            return
        }
        currentDisplayId.value = statusBarDisplayId
        if (removalListener == null) {
            // Lazy start this at the first invocation. it's fine to let it run also when the policy
            // is not selected anymore, as the job doesn't do anything until someone subscribes to
            // displayId.
            removalListener = monitorDisplayRemovals()
        }
    }

    private fun monitorDisplayRemovals(): Job {
        return backgroundScope.launchTraced("StatusBarTouchDisplayPolicy#monitorDisplayRemovals") {
            currentDisplayId.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                // When Active is false, no collect happens, and the old one is cancelled.
                // This is needed to prevent "availableDisplayIds" collection while nobody is
                // listening at the flow provided by this class.
                .collectLatest { active ->
                    if (active) {
                        availableDisplayIds.collect { availableIds ->
                            if (currentDisplayId.value !in availableIds) {
                                currentDisplayId.value = Display.DEFAULT_DISPLAY
                            }
                        }
                    }
                }
        }
    }

    private companion object {
        const val TAG = "StatusBarTouchDisplayPolicy"
    }
}
