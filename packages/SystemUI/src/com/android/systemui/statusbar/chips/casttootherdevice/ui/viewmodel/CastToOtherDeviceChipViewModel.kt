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

package com.android.systemui.statusbar.chips.casttootherdevice.ui.viewmodel

import androidx.annotation.DrawableRes
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.casttootherdevice.ui.view.EndCastToOtherDeviceDialogDelegate
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createDialogLaunchOnClickListener
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * View model for the cast-to-other-device chip, shown when sharing your phone screen content to a
 * different device. (Triggered from the Quick Settings Cast tile or from the Settings app.)
 */
@SysUISingleton
class CastToOtherDeviceChipViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val mediaProjectionChipInteractor: MediaProjectionChipInteractor,
    private val systemClock: SystemClock,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
) : OngoingActivityChipViewModel {
    override val chip: StateFlow<OngoingActivityChipModel> =
        // TODO(b/342169876): The MediaProjection APIs are not invoked for certain
        // cast-to-other-device events, like audio-only casting. We should also listen to
        // MediaRouter APIs to cover all cast events.
        mediaProjectionChipInteractor.projection
            .map { projectionModel ->
                when (projectionModel) {
                    is ProjectionChipModel.NotProjecting -> OngoingActivityChipModel.Hidden
                    is ProjectionChipModel.Projecting -> {
                        if (projectionModel.type != ProjectionChipModel.Type.CAST_TO_OTHER_DEVICE) {
                            OngoingActivityChipModel.Hidden
                        } else {
                            createCastToOtherDeviceChip(projectionModel)
                        }
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden)

    /** Stops the currently active projection. */
    private fun stopProjecting() {
        mediaProjectionChipInteractor.stopProjecting()
    }

    private fun createCastToOtherDeviceChip(
        state: ProjectionChipModel.Projecting,
    ): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown(
            icon =
                Icon.Resource(
                    CAST_TO_OTHER_DEVICE_ICON,
                    ContentDescription.Resource(R.string.accessibility_casting),
                ),
            // TODO(b/332662551): Maybe use a MediaProjection API to fetch this time.
            startTimeMs = systemClock.elapsedRealtime(),
            createDialogLaunchOnClickListener(
                createCastToOtherDeviceDialogDelegate(state),
                dialogTransitionAnimator,
            ),
        )
    }

    private fun createCastToOtherDeviceDialogDelegate(state: ProjectionChipModel.Projecting) =
        EndCastToOtherDeviceDialogDelegate(
            endMediaProjectionDialogHelper,
            stopAction = this::stopProjecting,
            state,
        )

    companion object {
        @DrawableRes val CAST_TO_OTHER_DEVICE_ICON = R.drawable.ic_cast_connected
    }
}
