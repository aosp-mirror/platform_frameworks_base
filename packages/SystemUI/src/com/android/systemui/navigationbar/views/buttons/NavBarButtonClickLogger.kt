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

package com.android.systemui.navigationbar.views.buttons

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.NavBarButtonClickLog
import javax.inject.Inject

class NavBarButtonClickLogger
@Inject
constructor(@NavBarButtonClickLog private val buffer: LogBuffer) {
    fun logHomeButtonClick() {
        buffer.log(TAG, LogLevel.DEBUG, {}, { "Home Button Triggered" })
    }

    fun logBackButtonClick() {
        buffer.log(TAG, LogLevel.DEBUG, {}, { "Back Button Triggered" })
    }

    fun logRecentsButtonClick() {
        buffer.log(TAG, LogLevel.DEBUG, {}, { "Recents Button Triggered" })
    }

    fun logImeSwitcherClick() {
        buffer.log(TAG, LogLevel.DEBUG, {}, { "Ime Switcher Triggered" })
    }

    fun logAccessibilityButtonClick() {
        buffer.log(TAG, LogLevel.DEBUG, {}, { "Accessibility Button Triggered" })
    }
}

private const val TAG = "NavBarButtonClick"
