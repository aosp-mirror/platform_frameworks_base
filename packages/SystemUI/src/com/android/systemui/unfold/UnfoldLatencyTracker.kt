/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.unfold

import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import com.android.internal.util.LatencyTracker
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.keyguard.ScreenLifecycle
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Logs performance metrics regarding time to turn the inner screen on.
 *
 * This class assumes that [onFoldEvent] is always called before [onScreenTurnedOn].
 *
 * This should be used from only one process.
 *
 * For now, the focus is on the time the inner display is visible, but in the future, it is easily
 * possible to monitor the time to go from the inner screen to the outer.
 */
@SysUISingleton
class UnfoldLatencyTracker
@Inject
constructor(
    private val latencyTracker: LatencyTracker,
    private val deviceStateManager: DeviceStateManager,
    @UiBackground private val uiBgExecutor: Executor,
    private val context: Context,
    private val screenLifecycle: ScreenLifecycle
) : ScreenLifecycle.Observer {

    private var folded: Boolean? = null
    private val foldStateListener = FoldStateListener(context)
    private val isFoldable: Boolean
        get() =
            context
                .resources
                .getIntArray(com.android.internal.R.array.config_foldedDeviceStates)
                .isNotEmpty()

    /** Registers for relevant events only if the device is foldable. */
    fun init() {
        if (!isFoldable) {
            return
        }
        deviceStateManager.registerCallback(uiBgExecutor, foldStateListener)
        screenLifecycle.addObserver(this)
    }

    /**
     * To be called when the screen becomes visible.
     *
     * This is safe to call also when unsure whether the device is not a foldable, as it emits the
     * end action event only if we previously received a fold state.
     */
    override fun onScreenTurnedOn() {
        if (folded == false) {
            latencyTracker.onActionEnd(LatencyTracker.ACTION_SWITCH_DISPLAY_UNFOLD)
        }
    }

    private fun onFoldEvent(folded: Boolean) {
        if (this.folded != folded) {
            this.folded = folded
            if (!folded) { // unfolding started
                latencyTracker.onActionStart(LatencyTracker.ACTION_SWITCH_DISPLAY_UNFOLD)
            }
        }
    }

    private inner class FoldStateListener(context: Context) :
        DeviceStateManager.FoldStateListener(context, { onFoldEvent(it) })
}
