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
import android.graphics.Rect
import android.graphics.Region
import android.view.View
import android.view.ViewTreeObserver.InternalInsetsInfo
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.devicePosture
import com.android.systemui.statusbar.policy.onConfigChanged
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStateModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.dialog.shared.model.streamLabel
import com.android.systemui.volume.dialog.sliders.domain.interactor.VolumeDialogSlidersInteractor
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.suspendCancellableCoroutine

/** Provides a state for the Volume Dialog. */
@VolumeDialogScope
class VolumeDialogViewModel
@Inject
constructor(
    private val context: Context,
    dialogVisibilityInteractor: VolumeDialogVisibilityInteractor,
    volumeDialogSlidersInteractor: VolumeDialogSlidersInteractor,
    volumeDialogStateInteractor: VolumeDialogStateInteractor,
    devicePostureController: DevicePostureController,
    configurationController: ConfigurationController,
) {

    val motionState: Flow<Int> =
        combine(
            devicePostureController.devicePosture(),
            configurationController.onConfigChanged.onStart {
                emit(context.resources.configuration)
            },
        ) { devicePosture, configuration ->
            if (shouldOffsetVolumeDialog(devicePosture, configuration)) {
                R.id.volume_dialog_half_folded_constraint_set
            } else {
                R.id.volume_dialog_constraint_set
            }
        }
    val dialogVisibilityModel: Flow<VolumeDialogVisibilityModel> =
        dialogVisibilityInteractor.dialogVisibility
    val dialogTitle: Flow<String> =
        combine(
                volumeDialogStateInteractor.volumeDialogState,
                volumeDialogSlidersInteractor.sliders.map { it.slider },
            ) { state: VolumeDialogStateModel, sliderType: VolumeDialogSliderType ->
                state.streamModels[sliderType.audioStream]?.let { model ->
                    context.getString(R.string.volume_dialog_title, model.streamLabel(context))
                }
            }
            .filterNotNull()

    private val touchableBoundsViews: MutableCollection<View> = mutableSetOf()

    /** @return true when the foldable device screen curve is in the way of the volume dialog */
    private fun shouldOffsetVolumeDialog(devicePosture: Int, config: Configuration): Boolean {
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isHalfOpen = devicePosture == DevicePostureController.DEVICE_POSTURE_HALF_OPENED
        return isLandscape && isHalfOpen
    }

    fun fillTouchableBounds(internalInsetsInfo: InternalInsetsInfo) {
        for (child in touchableBoundsViews) {
            val boundsRect = Rect()
            internalInsetsInfo.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION)

            child.getBoundsInWindow(boundsRect, false)
            internalInsetsInfo.touchableRegion.op(boundsRect, Region.Op.UNION)
        }
    }

    suspend fun addTouchableBounds(vararg views: View): Nothing = suspendCancellableCoroutine {
        touchableBoundsViews.addAll(views)
        it.invokeOnCancellation { touchableBoundsViews.removeAll(views.toSet()) }
    }
}
