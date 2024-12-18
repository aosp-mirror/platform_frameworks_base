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
import com.android.systemui.statusbar.phone.ManagedProfileController
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

val ManagedProfileController.hasActiveWorkProfile: Flow<Boolean>
    get() = conflatedCallbackFlow {
        val callback =
            object : ManagedProfileController.Callback {
                override fun onManagedProfileChanged() {
                    trySend(hasActiveProfile())
                }
                override fun onManagedProfileRemoved() {
                    // no-op, because the other callback will also be called.
                }
            }
        addCallback(callback) // calls onManagedProfileChanged
        awaitClose { removeCallback(callback) }
    }
