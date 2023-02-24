/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.temporarydisplay

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel

/** A logger for temporary view changes -- see [TemporaryViewDisplayController]. */
open class TemporaryViewLogger(
    internal val buffer: LogBuffer,
    internal val tag: String,
) {
    /** Logs that we added the chip to a new window. */
    fun logChipAddition() {
        buffer.log(tag, LogLevel.DEBUG, {}, { "Chip added" })
    }

    /** Logs that we removed the chip for the given [reason]. */
    fun logChipRemoval(reason: String) {
        buffer.log(tag, LogLevel.DEBUG, { str1 = reason }, { "Chip removed due to $str1" })
    }
}
