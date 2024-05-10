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

package com.android.settingslib.spa.framework.compose

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

const val ENABLE_LOG_COMPOSITIONS = false

data class LogCompositionsRef(var count: Int)

// Note the inline function below which ensures that this function is essentially
// copied at the call site to ensure that its logging only recompositions from the
// original call site.
@Suppress("NOTHING_TO_INLINE")
@Composable
inline fun LogCompositions(tag: String, msg: String) {
    if (ENABLE_LOG_COMPOSITIONS) {
        val ref = remember { LogCompositionsRef(0) }
        SideEffect { ref.count++ }
        Log.d(tag, "Compositions $msg: ${ref.count}")
    }
}
