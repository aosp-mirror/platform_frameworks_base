/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Provides access to bouncer-related application state. */
@SysUISingleton
class BouncerRepository
@Inject
constructor(
    private val flags: FeatureFlagsClassic,
) {
    private val _message = MutableStateFlow<String?>(null)
    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Whether the user switcher should be displayed within the bouncer UI on large screens. */
    val isUserSwitcherVisible: Boolean
        get() = flags.isEnabled(Flags.FULL_SCREEN_USER_SWITCHER)

    fun setMessage(message: String?) {
        _message.value = message
    }
}
