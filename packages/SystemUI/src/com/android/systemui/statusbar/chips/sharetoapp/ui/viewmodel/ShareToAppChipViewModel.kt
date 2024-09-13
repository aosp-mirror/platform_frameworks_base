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

import android.content.Context
import androidx.annotation.DrawableRes
import com.android.internal.jank.Cuj
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.sharetoapp.ui.view.EndShareToAppDialogDelegate
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ChipTransitionHelper
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
    private val context: Context,
    private val mediaProjectionChipInteractor: MediaProjectionChipInteractor,
    private val systemClock: SystemClock,
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    @StatusBarChipsLog private val logger: LogBuffer,
) : OngoingActivityChipViewModel {
    private val internalChip =
        mediaProjectionChipInteractor.projection
            .map { projectionModel ->
                when (projectionModel) {
                    is ProjectionChipModel.NotProjecting -> OngoingActivityChipModel.Hidden()
                    is ProjectionChipModel.Projecting -> {
                        if (projectionModel.type != ProjectionChipModel.Type.SHARE_TO_APP) {
                            OngoingActivityChipModel.Hidden()
                        } else {
                            createShareToAppChip(projectionModel)
                        }
                    }
                }
            }
            // See b/347726238 for [SharingStarted.Lazily] reasoning.
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Hidden())

    private val chipTransitionHelper = ChipTransitionHelper(scope)

    override val chip: StateFlow<OngoingActivityChipModel> =
        chipTransitionHelper.createChipFlow(internalChip)

    /**
     * Notifies this class that the user just stopped a screen recording from the dialog that's
     * shown when you tap the recording chip.
     */
    fun onRecordingStoppedFromDialog() {
        // When a screen recording is active, share-to-app is also active (screen recording is just
        // a special case of share-to-app, where the specific app receiving the share is System UI).
        // When a screen recording is stopped, we immediately hide the screen recording chip in
        // [com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel].
        // We *also* need to immediately hide the share-to-app chip so it doesn't briefly show.
        // See b/350891338.
        chipTransitionHelper.onActivityStoppedFromDialog()
    }

    /** Stops the currently active projection. */
    private fun stopProjectingFromDialog() {
        logger.log(TAG, LogLevel.INFO, {}, { "Stop sharing requested from dialog" })
        chipTransitionHelper.onActivityStoppedFromDialog()
        mediaProjectionChipInteractor.stopProjecting()
    }

    private fun createShareToAppChip(
        state: ProjectionChipModel.Projecting,
    ): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown.Timer(
            icon =
                OngoingActivityChipModel.ChipIcon.SingleColorIcon(
                    Icon.Resource(
                        SHARE_TO_APP_ICON,
                        ContentDescription.Resource(R.string.share_to_app_chip_accessibility_label),
                    )
                ),
            colors = ColorsModel.Red,
            // TODO(b/332662551): Maybe use a MediaProjection API to fetch this time.
            startTimeMs = systemClock.elapsedRealtime(),
            createDialogLaunchOnClickListener(
                createShareToAppDialogDelegate(state),
                dialogTransitionAnimator,
                DialogCuj(
                    Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP,
                    tag = "Share to app",
                ),
                logger,
                TAG,
            ),
        )
    }

    private fun createShareToAppDialogDelegate(state: ProjectionChipModel.Projecting) =
        EndShareToAppDialogDelegate(
            endMediaProjectionDialogHelper,
            context,
            stopAction = this::stopProjectingFromDialog,
            state,
        )

    companion object {
        @DrawableRes val SHARE_TO_APP_ICON = R.drawable.ic_present_to_all
        private const val TAG = "ShareToAppVM"
    }
}
