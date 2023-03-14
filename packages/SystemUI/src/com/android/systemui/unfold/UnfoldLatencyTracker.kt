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

import android.content.ContentResolver
import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import android.util.Log
import com.android.internal.util.LatencyTracker
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider.Companion.areAnimationsEnabled
import com.android.systemui.util.Compile
import java.util.Optional
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
    private val transitionProgressProvider: Optional<UnfoldTransitionProgressProvider>,
    @UiBackground private val uiBgExecutor: Executor,
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val screenLifecycle: ScreenLifecycle
) : ScreenLifecycle.Observer, TransitionProgressListener {

    private var folded: Boolean? = null
    private var isTransitionEnabled: Boolean? = null
    private val foldStateListener = FoldStateListener(context)
    private val isFoldable: Boolean
        get() =
            context.resources
                .getIntArray(com.android.internal.R.array.config_foldedDeviceStates)
                .isNotEmpty()

    /** Registers for relevant events only if the device is foldable. */
    fun init() {
        if (!isFoldable) {
            return
        }
        deviceStateManager.registerCallback(uiBgExecutor, foldStateListener)
        screenLifecycle.addObserver(this)
        if (transitionProgressProvider.isPresent) {
            // Might not be present if the device is not a foldable device or unfold transition
            // is disabled in the device configuration
            transitionProgressProvider.get().addCallback(this)
        }
    }

    /**
     * To be called when the screen becomes visible.
     *
     * This is safe to call also when unsure whether the device is not a foldable, as it emits the
     * end action event only if we previously received a fold state.
     */
    override fun onScreenTurnedOn() {
        if (DEBUG) {
            Log.d(
                TAG,
                "onScreenTurnedOn: folded = $folded, isTransitionEnabled = $isTransitionEnabled"
            )
        }

        // We use onScreenTurnedOn event to finish tracking only if we are not playing
        // the unfold animation (e.g. it could be disabled because of battery saver).
        // When animation is enabled finishing of the tracking will be done in onTransitionStarted.
        if (folded == false && isTransitionEnabled == false) {
            latencyTracker.onActionEnd(LatencyTracker.ACTION_SWITCH_DISPLAY_UNFOLD)

            if (DEBUG) {
                Log.d(TAG, "onScreenTurnedOn: ending ACTION_SWITCH_DISPLAY_UNFOLD")
            }
        }
    }

    /**
     * This callback is used to end the metric when the unfold animation is enabled because it could
     * add an additional delay to synchronize with launcher.
     */
    override fun onTransitionStarted() {
        if (DEBUG) {
            Log.d(
                TAG,
                "onTransitionStarted: folded = $folded, isTransitionEnabled = $isTransitionEnabled"
            )
        }

        if (folded == false && isTransitionEnabled == true) {
            latencyTracker.onActionEnd(LatencyTracker.ACTION_SWITCH_DISPLAY_UNFOLD)

            if (DEBUG) {
                Log.d(TAG, "onTransitionStarted: ending ACTION_SWITCH_DISPLAY_UNFOLD")
            }
        }
    }

    private fun onFoldEvent(folded: Boolean) {
        val oldFolded = this.folded

        if (oldFolded != folded) {
            this.folded = folded

            if (DEBUG) {
                Log.d(TAG, "Received onFoldEvent = $folded")
            }

            // Do not start tracking when oldFolded is null, this means that this is the first
            // onFoldEvent after booting the device or starting SystemUI and not actual folding or
            // unfolding the device.
            if (oldFolded != null && !folded) {
                // Unfolding started
                latencyTracker.onActionStart(LatencyTracker.ACTION_SWITCH_DISPLAY_UNFOLD)
                isTransitionEnabled =
                    transitionProgressProvider.isPresent && contentResolver.areAnimationsEnabled()

                if (DEBUG) {
                    Log.d(
                        TAG,
                        "Starting ACTION_SWITCH_DISPLAY_UNFOLD, " +
                            "isTransitionEnabled = $isTransitionEnabled"
                    )
                }
            }
        }
    }

    private inner class FoldStateListener(context: Context) :
        DeviceStateManager.FoldStateListener(context, { onFoldEvent(it) })
}

private const val TAG = "UnfoldLatencyTracker"
private val DEBUG = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.VERBOSE)
