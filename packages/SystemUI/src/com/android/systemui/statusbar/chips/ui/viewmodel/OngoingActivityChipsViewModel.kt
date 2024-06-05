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

package com.android.systemui.statusbar.chips.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.chips.call.domain.interactor.CallChipInteractor
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * View model deciding which ongoing activity chip to show in the status bar.
 *
 * There may be multiple ongoing activities at the same time, but we can only ever show one chip at
 * any one time (for now). This class decides which ongoing activity to show if there are multiple.
 */
@SysUISingleton
class OngoingActivityChipsViewModel
@Inject
constructor(
    @Application scope: CoroutineScope,
    screenRecordChipInteractor: ScreenRecordChipInteractor,
    mediaProjectionChipInteractor: MediaProjectionChipInteractor,
    callChipInteractor: CallChipInteractor,
) {

    /**
     * A flow modeling the chip that should be shown in the status bar after accounting for possibly
     * multiple ongoing activities.
     *
     * [com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment] is responsible for
     * actually displaying the chip.
     */
    val chip: StateFlow<OngoingActivityChipModel> =
        combine(
                screenRecordChipInteractor.chip,
                mediaProjectionChipInteractor.chip,
                callChipInteractor.chip
            ) { screenRecord, mediaProjection, call ->
                // This `when` statement shows the priority order of the chips
                when {
                    // Screen recording also activates the media projection APIs, so whenever the
                    // screen recording chip is active, the media projection chip would also be
                    // active. We want the screen-recording-specific chip shown in this case, so we
                    // give the screen recording chip priority. See b/296461748.
                    screenRecord is OngoingActivityChipModel.Shown -> screenRecord
                    mediaProjection is OngoingActivityChipModel.Shown -> mediaProjection
                    else -> call
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden)
}
