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
import androidx.annotation.DrawableRes
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import com.android.systemui.statusbar.chips.screenrecord.domain.model.ScreenRecordChipModel
import com.android.systemui.statusbar.chips.screenrecord.ui.view.EndScreenRecordingDialogDelegate
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

/** View model for the screen recording chip shown in the status bar. */
@SysUISingleton
class ScreenRecordChipViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val interactor: ScreenRecordChipInteractor,
    private val systemClock: SystemClock,
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
) : OngoingActivityChipViewModel {
    override val chip: StateFlow<OngoingActivityChipModel> =
        interactor.screenRecordState
            .map { state ->
                when (state) {
                    is ScreenRecordChipModel.DoingNothing -> OngoingActivityChipModel.Hidden
                    // TODO(b/332662551): Implement the 3-2-1 countdown chip.
                    is ScreenRecordChipModel.Starting -> OngoingActivityChipModel.Hidden
                    is ScreenRecordChipModel.Recording -> {
                        OngoingActivityChipModel.Shown(
                            // TODO(b/332662551): Also provide a content description.
                            icon = Icon.Resource(ICON, contentDescription = null),
                            colors = ColorsModel.Red,
                            startTimeMs = systemClock.elapsedRealtime(),
                            createDialogLaunchOnClickListener(
                                createDelegate(state.recordedTask),
                                dialogTransitionAnimator,
                            ),
                        )
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden)

    private fun createDelegate(
        recordedTask: ActivityManager.RunningTaskInfo?
    ): EndScreenRecordingDialogDelegate {
        return EndScreenRecordingDialogDelegate(
            endMediaProjectionDialogHelper,
            stopAction = interactor::stopRecording,
            recordedTask,
        )
    }

    companion object {
        @DrawableRes val ICON = R.drawable.ic_screenrecord
    }
}
