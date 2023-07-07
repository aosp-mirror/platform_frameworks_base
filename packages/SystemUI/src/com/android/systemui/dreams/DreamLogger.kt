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

package com.android.systemui.dreams

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.DreamLog
import javax.inject.Inject

/** Logs dream-related stuff to a {@link LogBuffer}. */
class DreamLogger @Inject constructor(@DreamLog private val buffer: LogBuffer) {
    /** Logs a debug message to the buffer. */
    fun d(tag: String, message: String) {
        buffer.log(tag, LogLevel.DEBUG, { str1 = message }, { message })
    }
}
