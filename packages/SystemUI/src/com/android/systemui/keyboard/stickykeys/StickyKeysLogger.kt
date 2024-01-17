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

package com.android.systemui.keyboard.stickykeys

import com.android.systemui.keyboard.stickykeys.shared.model.Locked
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.KeyboardLog
import javax.inject.Inject

private const val TAG = "stickyKeys"

class StickyKeysLogger @Inject constructor(@KeyboardLog private val buffer: LogBuffer) {
    fun logNewStickyKeysReceived(linkedHashMap: Map<ModifierKey, Locked>) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            { str1 = linkedHashMap.toString() },
            { "new sticky keys state received: $str1" }
        )
    }
}