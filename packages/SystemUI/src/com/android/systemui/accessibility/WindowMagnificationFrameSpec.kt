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

package com.android.systemui.accessibility

import android.util.Size

data class WindowMagnificationFrameSpec(val index: Int, val size: Size) {

    companion object {
        private fun throwInvalidWindowMagnificationFrameSpec(s: String?): Nothing {
            throw NumberFormatException("Invalid WindowMagnificationFrameSpec: \"$s\"")
        }

        @JvmStatic fun serialize(index: Int, size: Size) = "$index,$size"

        @JvmStatic
        fun deserialize(s: String): WindowMagnificationFrameSpec {
            val separatorIndex = s.indexOf(',')
            if (separatorIndex < 0) {
                throwInvalidWindowMagnificationFrameSpec(s)
            }
            return try {
                WindowMagnificationFrameSpec(
                    s.substring(0, separatorIndex).toInt(),
                    Size.parseSize(s.substring(separatorIndex + 1))
                )
            } catch (e: NumberFormatException) {
                throwInvalidWindowMagnificationFrameSpec(s)
            }
        }
    }
}
