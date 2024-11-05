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

package com.android.systemui.volume.dialog.ui.viewmodel

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import androidx.annotation.GravityInt
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.devicePosture
import com.android.systemui.statusbar.policy.onConfigChanged
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Exposes dialog [GravityInt] for use in the UI layer. */
@VolumeDialogScope
class VolumeDialogGravityViewModel
@Inject
constructor(
    @Application private val context: Context,
    @VolumeDialog private val coroutineScope: CoroutineScope,
    @UiBackground private val uiBackgroundCoroutineContext: CoroutineContext,
    configurationController: ConfigurationController,
    private val devicePostureController: DevicePostureController,
) {

    @GravityInt private var originalGravity: Int = context.getAbsoluteGravity()

    val dialogGravity: Flow<Int> =
        combine(
                devicePostureController.devicePosture(),
                configurationController.onConfigChanged.onEach { onConfigurationChanged() },
            ) { devicePosture, configuration ->
                context.calculateGravity(devicePosture, configuration)
            }
            .stateIn(
                scope = coroutineScope,
                started = SharingStarted.Eagerly,
                context.calculateGravity(),
            )

    private suspend fun onConfigurationChanged() {
        withContext(uiBackgroundCoroutineContext) { originalGravity = context.getAbsoluteGravity() }
    }

    @GravityInt
    private fun Context.calculateGravity(
        devicePosture: Int = devicePostureController.devicePosture,
        config: Configuration = resources.configuration,
    ): Int {
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isHalfOpen = devicePosture == DevicePostureController.DEVICE_POSTURE_HALF_OPENED
        val gravity =
            if (isLandscape && isHalfOpen) {
                originalGravity or Gravity.TOP
            } else {
                originalGravity
            }
        return getAbsoluteGravity(gravity)
    }
}

@GravityInt
private fun Context.getAbsoluteGravity(
    gravity: Int = resources.getInteger(R.integer.volume_dialog_gravity)
): Int = with(resources) { Gravity.getAbsoluteGravity(gravity, configuration.layoutDirection) }
