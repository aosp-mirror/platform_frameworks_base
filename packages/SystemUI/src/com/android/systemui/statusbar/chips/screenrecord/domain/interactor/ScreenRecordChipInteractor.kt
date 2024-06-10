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

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.ScreenRecordRepository
import com.android.systemui.statusbar.chips.domain.interactor.OngoingActivityChipInteractor
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Interactor for the screen recording chip shown in the status bar. */
@SysUISingleton
class ScreenRecordChipInteractor
@Inject
constructor(
    @Application scope: CoroutineScope,
    screenRecordRepository: ScreenRecordRepository,
    val systemClock: SystemClock,
) : OngoingActivityChipInteractor {
    override val chip: StateFlow<OngoingActivityChipModel> =
        screenRecordRepository.screenRecordState
            .map { state ->
                when (state) {
                    is ScreenRecordModel.DoingNothing,
                    // TODO(b/332662551): Implement the 3-2-1 countdown chip.
                    is ScreenRecordModel.Starting -> OngoingActivityChipModel.Hidden
                    is ScreenRecordModel.Recording ->
                        OngoingActivityChipModel.Shown(
                            // TODO(b/332662551): Also provide a content description.
                            icon =
                                Icon.Resource(
                                    R.drawable.stat_sys_screen_record,
                                    contentDescription = null
                                ),
                            startTimeMs = systemClock.elapsedRealtime()
                        ) {
                            // TODO(b/332662551): Implement the pause dialog.
                        }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden)
}
