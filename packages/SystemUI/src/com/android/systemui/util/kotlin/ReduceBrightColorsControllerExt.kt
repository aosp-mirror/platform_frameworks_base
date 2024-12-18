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

package com.android.systemui.util.kotlin

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.qs.ReduceBrightColorsController
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

fun ReduceBrightColorsController.isEnabled(): Flow<Boolean> {
    return conflatedCallbackFlow {
            val callback =
                object : ReduceBrightColorsController.Listener {
                    override fun onActivated(activated: Boolean) {
                        trySend(activated)
                    }
                }
            addCallback(callback)
            awaitClose { removeCallback(callback) }
        }
        .onStart { emit(isReduceBrightColorsActivated) }
}
