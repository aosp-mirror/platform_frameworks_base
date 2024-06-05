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
 */
@SysUISingleton
class OngoingCallRepository @Inject constructor() {
    private val _hasOngoingCall = MutableStateFlow(false)
    /** True if there's currently an ongoing call notification and false otherwise. */
    val hasOngoingCall: StateFlow<Boolean> = _hasOngoingCall.asStateFlow()

    /**
     * Sets whether there's currently an ongoing call notification. Should only be set from
     * [com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController].
     */
    fun setHasOngoingCall(hasOngoingCall: Boolean) {
        _hasOngoingCall.value = hasOngoingCall
    }
}
