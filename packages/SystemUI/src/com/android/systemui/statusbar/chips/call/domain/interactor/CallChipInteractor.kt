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

package com.android.systemui.statusbar.chips.call.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.OngoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.domain.interactor.OngoingCallInteractor
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Interactor for the ongoing phone call chip shown in the status bar. */
@SysUISingleton
class CallChipInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    ongoingCallInteractor: OngoingCallInteractor,
    repository: OngoingCallRepository,
    @StatusBarChipsLog private val logger: LogBuffer,
) {
    val ongoingCallState: StateFlow<OngoingCallModel> =
        (if (StatusBarChipsModernization.isEnabled)
            ongoingCallInteractor.ongoingCallState
        else
            repository.ongoingCallState)
            .onEach {
                logger.log(
                    TAG,
                    LogLevel.INFO,
                    { str1 = it::class.simpleName },
                    { "State: $str1" }
                )
            }
            .stateIn(
                scope,
                SharingStarted.Lazily,
                OngoingCallModel.NoCall
            )

    companion object {
        private val TAG = "OngoingCall".pad()
    }
}