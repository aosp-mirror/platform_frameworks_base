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

package com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel

import android.app.ActivityManager
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
import com.android.systemui.screenrecord.data.model.ScreenRecordModel.Starting.Companion.toCountdownSeconds
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import com.android.systemui.statusbar.chips.screenrecord.domain.model.ScreenRecordChipModel
import com.android.systemui.statusbar.chips.screenrecord.ui.view.EndScreenRecordingDialogDelegate
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ChipTransitionHelper
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createDialogLaunchOnClickListener
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** View model for the screen recording chip shown in the status bar. */
@SysUISingleton
class ScreenRecordChipViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val interactor: ScreenRecordChipInteractor,
    private val shareToAppChipViewModel: ShareToAppChipViewModel,
    private val systemClock: SystemClock,
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    @StatusBarChipsLog private val logger: LogBuffer,
) : OngoingActivityChipViewModel {

    /** A direct mapping from [ScreenRecordChipModel] to [OngoingActivityChipModel]. */
    private val simpleChip =
        interactor.screenRecordState
            .map { state ->
                when (state) {
                    is ScreenRecordChipModel.DoingNothing -> OngoingActivityChipModel.Hidden()
                    is ScreenRecordChipModel.Starting -> {
                        OngoingActivityChipModel.Shown.Countdown(
                            colors = ColorsModel.Red,
                            secondsUntilStarted = state.millisUntilStarted.toCountdownSeconds(),
                        )
                    }
                    is ScreenRecordChipModel.Recording -> {
                        OngoingActivityChipModel.Shown.Timer(
                            icon =
                                OngoingActivityChipModel.ChipIcon.SingleColorIcon(
                                    Icon.Resource(
                                        ICON,
                                        ContentDescription.Resource(
                                            R.string.screenrecord_ongoing_screen_only,
                                        ),
                                    )
                                ),
                            colors = ColorsModel.Red,
                            startTimeMs = systemClock.elapsedRealtime(),
                            createDialogLaunchOnClickListener(
                                createDelegate(state.recordedTask),
                                dialogTransitionAnimator,
                                DialogCuj(
                                    Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP,
                                    tag = "Screen record",
                                ),
                                logger,
                                TAG,
                            ),
                        )
                    }
                }
            }
            // See b/347726238 for [SharingStarted.Lazily] reasoning.
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Hidden())

    /**
     * The screen record chip to show that also ensures that the start time doesn't change once we
     * enter the recording state. If we change the start time while we're recording, the chronometer
     * could skip a second. See b/349620526.
     */
    private val chipWithConsistentTimer: StateFlow<OngoingActivityChipModel> =
        simpleChip
            .pairwise(initialValue = OngoingActivityChipModel.Hidden())
            .map { (old, new) ->
                if (
                    old is OngoingActivityChipModel.Shown.Timer &&
                        new is OngoingActivityChipModel.Shown.Timer
                ) {
                    new.copy(startTimeMs = old.startTimeMs)
                } else {
                    new
                }
            }
            // See b/347726238 for [SharingStarted.Lazily] reasoning.
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Hidden())

    private val chipTransitionHelper = ChipTransitionHelper(scope)

    override val chip: StateFlow<OngoingActivityChipModel> =
        chipTransitionHelper.createChipFlow(chipWithConsistentTimer)

    private fun createDelegate(
        recordedTask: ActivityManager.RunningTaskInfo?
    ): EndScreenRecordingDialogDelegate {
        return EndScreenRecordingDialogDelegate(
            endMediaProjectionDialogHelper,
            context,
            stopAction = this::stopRecordingFromDialog,
            recordedTask,
        )
    }

    private fun stopRecordingFromDialog() {
        logger.log(TAG, LogLevel.INFO, {}, { "Stop recording requested from dialog" })
        chipTransitionHelper.onActivityStoppedFromDialog()
        shareToAppChipViewModel.onRecordingStoppedFromDialog()
        interactor.stopRecording()
    }

    companion object {
        @DrawableRes val ICON = R.drawable.ic_screenrecord
        private const val TAG = "ScreenRecordVM"
    }
}
