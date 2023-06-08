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

package com.android.systemui.statusbar.policy

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * Returns a [Flow] that emits events whenever a [NotificationEntry] enters or exists the "heads up"
 * state.
 */
val HeadsUpManager.headsUpEvents: Flow<Pair<NotificationEntry, Boolean>>
    get() = conflatedCallbackFlow {
        val listener =
            object : OnHeadsUpChangedListener {
                override fun onHeadsUpStateChanged(entry: NotificationEntry, isHeadsUp: Boolean) {
                    trySend(entry to isHeadsUp)
                }
            }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }
