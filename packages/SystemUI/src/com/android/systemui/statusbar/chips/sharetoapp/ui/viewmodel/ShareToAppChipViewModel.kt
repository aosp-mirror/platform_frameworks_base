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

package com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel

import androidx.annotation.DrawableRes
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.sharetoapp.ui.view.EndShareToAppDialogDelegate
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
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
 * View model for the share-to-app chip, shown when sharing your phone screen content to another app
 * on the same device. (Triggered from within each individual app.)
 */
@SysUISingleton
class ShareToAppChipViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val mediaProjectionChipInteractor: MediaProjectionChipInteractor,
    private val systemClock: SystemClock,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
) : OngoingActivityChipViewModel {
    override val chip: StateFlow<OngoingActivityChipModel> =
        mediaProjectionChipInteractor.projection
            .map { projectionModel ->
                when (projectionModel) {
                    is ProjectionChipModel.NotProjecting -> OngoingActivityChipModel.Hidden
                    is ProjectionChipModel.Projecting -> {
                        if (projectionModel.type != ProjectionChipModel.Type.SHARE_TO_APP) {
                            OngoingActivityChipModel.Hidden
                        } else {
                            createShareToAppChip(projectionModel)
                        }
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden)

    /** Stops the currently active projection. */
    private fun stopProjecting() {
        mediaProjectionChipInteractor.stopProjecting()
    }

    private fun createShareToAppChip(
        state: ProjectionChipModel.Projecting,
    ): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown(
            // TODO(b/332662551): Use the right content description.
            icon = Icon.Resource(SHARE_TO_APP_ICON, contentDescription = null),
            colors = ColorsModel.Red,
            // TODO(b/332662551): Maybe use a MediaProjection API to fetch this time.
            startTimeMs = systemClock.elapsedRealtime(),
            createDialogLaunchOnClickListener(
                createShareToAppDialogDelegate(state),
                dialogTransitionAnimator
            ),
        )
    }

    private fun createShareToAppDialogDelegate(state: ProjectionChipModel.Projecting) =
        EndShareToAppDialogDelegate(
            endMediaProjectionDialogHelper,
            stopAction = this::stopProjecting,
            state,
        )

    companion object {
        @DrawableRes val SHARE_TO_APP_ICON = R.drawable.ic_present_to_all
    }
}
