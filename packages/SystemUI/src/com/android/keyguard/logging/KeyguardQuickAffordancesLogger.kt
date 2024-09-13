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

package com.android.keyguard.logging

import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.KeyguardQuickAffordancesLog
import javax.inject.Inject

class KeyguardQuickAffordancesLogger
@Inject
constructor(
    @KeyguardQuickAffordancesLog val buffer: LogBuffer,
) {
    fun logQuickAffordanceTapped(configKey: String?) {
        val (slotId, affordanceId) = configKey?.decode() ?: ("" to "")
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = affordanceId
                str2 = slotId
            },
            { "QuickAffordance tapped with id: $str1, in slot: $str2" }
        )
    }

    fun logQuickAffordanceTriggered(slotId: String, affordanceId: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = affordanceId
                str2 = slotId
            },
            { "QuickAffordance triggered with id: $str1, in slot: $str2" }
        )
    }

    fun logQuickAffordanceSelected(slotId: String, affordanceId: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = affordanceId
                str2 = slotId
            },
            { "QuickAffordance selected with id: $str1, in slot: $str2" }
        )
    }

    fun logUpdate(viewModel: KeyguardQuickAffordanceViewModel) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = viewModel.toString() },
            { "QuickAffordance updated: $str1" }
        )
    }

    private fun String.decode(): Pair<String, String> {
        val splitUp = this.split(DELIMITER)
        return Pair(splitUp[0], splitUp[1])
    }

    companion object {
        private const val TAG = "KeyguardQuickAffordancesLogger"
        private const val DELIMITER = "::"
    }
}
