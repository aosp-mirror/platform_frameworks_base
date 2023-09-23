/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import com.android.systemui.res.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.statusbar.policy.FlashlightController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class FlashlightQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val flashlightController: FlashlightController,
) : KeyguardQuickAffordanceConfig {

    private sealed class FlashlightState {

        abstract fun toLockScreenState(): KeyguardQuickAffordanceConfig.LockScreenState

        object On : FlashlightState() {
            override fun toLockScreenState(): KeyguardQuickAffordanceConfig.LockScreenState =
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    Icon.Resource(
                        R.drawable.qs_flashlight_icon_on,
                        ContentDescription.Resource(R.string.quick_settings_flashlight_label)
                    ),
                    ActivationState.Active
                )
        }

        object OffAvailable : FlashlightState() {
            override fun toLockScreenState(): KeyguardQuickAffordanceConfig.LockScreenState =
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    Icon.Resource(
                        R.drawable.qs_flashlight_icon_off,
                        ContentDescription.Resource(R.string.quick_settings_flashlight_label)
                    ),
                    ActivationState.Inactive
                )
        }

        object Unavailable : FlashlightState() {
            override fun toLockScreenState(): KeyguardQuickAffordanceConfig.LockScreenState =
                KeyguardQuickAffordanceConfig.LockScreenState.Hidden
        }
    }

    override val key: String
        get() = BuiltInKeyguardQuickAffordanceKeys.FLASHLIGHT

    override fun pickerName(): String = context.getString(R.string.quick_settings_flashlight_label)

    override val pickerIconResourceId: Int
        get() = R.drawable.ic_flashlight_off

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        conflatedCallbackFlow {
            val flashlightCallback =
                object : FlashlightController.FlashlightListener {
                    override fun onFlashlightChanged(enabled: Boolean) {
                        trySendWithFailureLogging(
                            if (enabled) {
                                FlashlightState.On.toLockScreenState()
                            } else {
                                FlashlightState.OffAvailable.toLockScreenState()
                            },
                            TAG
                        )
                    }

                    override fun onFlashlightError() {
                        trySendWithFailureLogging(
                            FlashlightState.OffAvailable.toLockScreenState(),
                            TAG
                        )
                    }

                    override fun onFlashlightAvailabilityChanged(available: Boolean) {
                        trySendWithFailureLogging(
                            if (!available) {
                                FlashlightState.Unavailable.toLockScreenState()
                            } else {
                                if (flashlightController.isEnabled) {
                                    FlashlightState.On.toLockScreenState()
                                } else {
                                    FlashlightState.OffAvailable.toLockScreenState()
                                }
                            },
                            TAG
                        )
                    }
                }

            flashlightController.addCallback(flashlightCallback)

            awaitClose { flashlightController.removeCallback(flashlightCallback) }
        }

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        flashlightController.setFlashlight(
            flashlightController.isAvailable && !flashlightController.isEnabled
        )
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
    }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState =
        if (flashlightController.isAvailable) {
            KeyguardQuickAffordanceConfig.PickerScreenState.Default()
        } else {
            KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
        }

    companion object {
        private const val TAG = "FlashlightQuickAffordanceConfig"
    }
}
