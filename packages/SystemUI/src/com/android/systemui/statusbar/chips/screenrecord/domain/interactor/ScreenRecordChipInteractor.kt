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

package com.android.systemui.statusbar.chips.screenrecord.domain.interactor

import androidx.annotation.DrawableRes
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.MediaProjectionRepository
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.ScreenRecordRepository
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.screenrecord.ui.view.EndScreenRecordingDialogDelegate
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createDialogLaunchOnClickListener
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Interactor for the screen recording chip shown in the status bar. */
// TODO(b/332662551): Convert this into a view model.
@SysUISingleton
class ScreenRecordChipInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val screenRecordRepository: ScreenRecordRepository,
    private val mediaProjectionRepository: MediaProjectionRepository,
    private val systemClock: SystemClock,
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
) : OngoingActivityChipViewModel {
    override val chip: StateFlow<OngoingActivityChipModel> =
        // ScreenRecordRepository has the main "is the screen being recorded?" state, and
        // MediaProjectionRepository has information about what specifically is being recorded (a
        // single app or the entire screen)
        combine(
                screenRecordRepository.screenRecordState,
                mediaProjectionRepository.mediaProjectionState,
            ) { screenRecordState, mediaProjectionState ->
                when (screenRecordState) {
                    is ScreenRecordModel.DoingNothing,
                    // TODO(b/332662551): Implement the 3-2-1 countdown chip.
                    is ScreenRecordModel.Starting -> OngoingActivityChipModel.Hidden
                    is ScreenRecordModel.Recording ->
                        OngoingActivityChipModel.Shown(
                            // TODO(b/332662551): Also provide a content description.
                            icon = Icon.Resource(ICON, contentDescription = null),
                            startTimeMs = systemClock.elapsedRealtime(),
                            createDialogLaunchOnClickListener(
                                createDelegate(mediaProjectionState),
                                dialogTransitionAnimator
                            ),
                        )
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden)

    /** Stops the recording. */
    fun stopRecording() {
        scope.launch { screenRecordRepository.stopRecording() }
    }

    private fun createDelegate(state: MediaProjectionState): EndScreenRecordingDialogDelegate {
        return EndScreenRecordingDialogDelegate(
            endMediaProjectionDialogHelper,
            this@ScreenRecordChipInteractor,
            state,
        )
    }

    companion object {
        @DrawableRes val ICON = R.drawable.ic_screenrecord
    }
}
