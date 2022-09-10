/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.Position
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.doze.DozeHost
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Defines interface for classes that encapsulate application state for the keyguard. */
interface KeyguardRepository {
    /**
     * Observable for whether the bottom area UI should animate the transition out of doze state.
     *
     * To learn more about doze state, please see [isDozing].
     */
    val animateBottomAreaDozingTransitions: StateFlow<Boolean>

    /**
     * Observable for the current amount of alpha that should be used for rendering the bottom area.
     * UI.
     */
    val bottomAreaAlpha: StateFlow<Float>

    /**
     * Observable of the relative offset of the lock-screen clock from its natural position on the
     * screen.
     */
    val clockPosition: StateFlow<Position>

    /**
     * Observable for whether the keyguard is showing.
     *
     * Note: this is also `true` when the lock-screen is occluded with an `Activity` "above" it in
     * the z-order (which is not really above the system UI window, but rather - the lock-screen
     * becomes invisible to reveal the "occluding activity").
     */
    val isKeyguardShowing: Flow<Boolean>

    /**
     * Observable for whether we are in doze state.
     *
     * Doze state is the same as "Always on Display" or "AOD". It is the state that the device can
     * enter to conserve battery when the device is locked and inactive.
     *
     * Note that it is possible for the system to be transitioning into doze while this flow still
     * returns `false`. In order to account for that, observers should also use the [dozeAmount]
     * flow to check if it's greater than `0`
     */
    val isDozing: Flow<Boolean>

    /**
     * Observable for the amount of doze we are currently in.
     *
     * While in doze state, this amount can change - driving a cycle of animations designed to avoid
     * pixel burn-in, etc.
     *
     * Also note that the value here may be greater than `0` while [isDozing] is still `false`, this
     * happens during an animation/transition into doze mode. An observer would be wise to account
     * for both flows if needed.
     */
    val dozeAmount: Flow<Float>

    /** Sets whether the bottom area UI should animate the transition out of doze state. */
    fun setAnimateDozingTransitions(animate: Boolean)

    /** Sets the current amount of alpha that should be used for rendering the bottom area. */
    fun setBottomAreaAlpha(alpha: Float)

    /**
     * Sets the relative offset of the lock-screen clock from its natural position on the screen.
     */
    fun setClockPosition(x: Int, y: Int)
}

/** Encapsulates application state for the keyguard. */
@SysUISingleton
class KeyguardRepositoryImpl
@Inject
constructor(
    statusBarStateController: StatusBarStateController,
    keyguardStateController: KeyguardStateController,
    dozeHost: DozeHost,
) : KeyguardRepository {
    private val _animateBottomAreaDozingTransitions = MutableStateFlow(false)
    override val animateBottomAreaDozingTransitions =
        _animateBottomAreaDozingTransitions.asStateFlow()

    private val _bottomAreaAlpha = MutableStateFlow(1f)
    override val bottomAreaAlpha = _bottomAreaAlpha.asStateFlow()

    private val _clockPosition = MutableStateFlow(Position(0, 0))
    override val clockPosition = _clockPosition.asStateFlow()

    override val isKeyguardShowing: Flow<Boolean> = conflatedCallbackFlow {
        val callback =
            object : KeyguardStateController.Callback {
                override fun onKeyguardShowingChanged() {
                    trySendWithFailureLogging(
                        keyguardStateController.isShowing,
                        TAG,
                        "updated isKeyguardShowing"
                    )
                }
            }

        keyguardStateController.addCallback(callback)
        // Adding the callback does not send an initial update.
        trySendWithFailureLogging(
            keyguardStateController.isShowing,
            TAG,
            "initial isKeyguardShowing"
        )

        awaitClose { keyguardStateController.removeCallback(callback) }
    }

    override val isDozing: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : DozeHost.Callback {
                        override fun onDozingChanged(isDozing: Boolean) {
                            trySendWithFailureLogging(isDozing, TAG, "updated isDozing")
                        }
                    }
                dozeHost.addCallback(callback)
                trySendWithFailureLogging(false, TAG, "initial isDozing: false")

                awaitClose { dozeHost.removeCallback(callback) }
            }
            .distinctUntilChanged()

    override val dozeAmount: Flow<Float> = conflatedCallbackFlow {
        val callback =
            object : StatusBarStateController.StateListener {
                override fun onDozeAmountChanged(linear: Float, eased: Float) {
                    trySendWithFailureLogging(eased, TAG, "updated dozeAmount")
                }
            }

        statusBarStateController.addCallback(callback)
        trySendWithFailureLogging(statusBarStateController.dozeAmount, TAG, "initial dozeAmount")

        awaitClose { statusBarStateController.removeCallback(callback) }
    }

    override fun setAnimateDozingTransitions(animate: Boolean) {
        _animateBottomAreaDozingTransitions.value = animate
    }

    override fun setBottomAreaAlpha(alpha: Float) {
        _bottomAreaAlpha.value = alpha
    }

    override fun setClockPosition(x: Int, y: Int) {
        _clockPosition.value = Position(x, y)
    }

    companion object {
        private const val TAG = "KeyguardRepositoryImpl"
    }
}
