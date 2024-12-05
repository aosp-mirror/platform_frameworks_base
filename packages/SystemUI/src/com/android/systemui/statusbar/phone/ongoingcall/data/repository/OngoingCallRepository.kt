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

package com.android.systemui.statusbar.phone.ongoingcall.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallLog
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository storing whether there's current an ongoing call notification.
 *
 * This class is used to break a dependency cycle between
 * [com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController] and
 * [com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore]. Instead, those two
 * classes both refer to this repository.
 * @deprecated Use [OngoingCallInteractor] instead.
 */
@Deprecated("Use OngoingCallInteractor instead")
@SysUISingleton
class OngoingCallRepository
@Inject
constructor(
    @OngoingCallLog private val logger: LogBuffer,
) {
    private val _ongoingCallState = MutableStateFlow<OngoingCallModel>(OngoingCallModel.NoCall)
    /** The current ongoing call state. */
    val ongoingCallState: StateFlow<OngoingCallModel> = _ongoingCallState.asStateFlow()

    /**
     * Sets the current ongoing call state, based on notifications. Should only be set from
     * [com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController].
     */
    fun setOngoingCallState(state: OngoingCallModel) {
        StatusBarChipsModernization.assertInLegacyMode()

        logger.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = state::class.simpleName },
            { "Repo#setOngoingCallState: $str1" },
        )
        _ongoingCallState.value = state
    }

    companion object {
        const val TAG = "OngoingCall"
    }
}
