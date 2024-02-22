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

package com.android.systemui.statusbar.notification.data.repository

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

class HeadsUpNotificationRepositoryImpl
@Inject
constructor(
    headsUpManager: HeadsUpManager,
) : HeadsUpNotificationRepository {
    override val hasPinnedHeadsUp: Flow<Boolean> = conflatedCallbackFlow {
        val listener =
            object : OnHeadsUpChangedListener {
                override fun onHeadsUpPinnedModeChanged(inPinnedMode: Boolean) {
                    trySend(headsUpManager.hasPinnedHeadsUp())
                }

                override fun onHeadsUpPinned(entry: NotificationEntry?) {
                    trySend(headsUpManager.hasPinnedHeadsUp())
                }

                override fun onHeadsUpUnPinned(entry: NotificationEntry?) {
                    trySend(headsUpManager.hasPinnedHeadsUp())
                }

                override fun onHeadsUpStateChanged(entry: NotificationEntry, isHeadsUp: Boolean) {
                    trySend(headsUpManager.hasPinnedHeadsUp())
                }
            }
        trySend(headsUpManager.hasPinnedHeadsUp())
        headsUpManager.addListener(listener)
        awaitClose { headsUpManager.removeListener(listener) }
    }
}

interface HeadsUpNotificationRepository {
    val hasPinnedHeadsUp: Flow<Boolean>
}
