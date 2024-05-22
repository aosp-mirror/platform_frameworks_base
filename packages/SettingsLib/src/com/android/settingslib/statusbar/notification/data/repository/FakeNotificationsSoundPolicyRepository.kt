/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.statusbar.notification.data.repository

import android.app.NotificationManager
import android.provider.Settings
import com.android.settingslib.statusbar.notification.data.model.ZenMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeNotificationsSoundPolicyRepository : NotificationsSoundPolicyRepository {

    private val mutableNotificationPolicy = MutableStateFlow<NotificationManager.Policy?>(null)
    override val notificationPolicy: StateFlow<NotificationManager.Policy?>
        get() = mutableNotificationPolicy.asStateFlow()

    private val mutableZenMode = MutableStateFlow<ZenMode?>(ZenMode(Settings.Global.ZEN_MODE_OFF))
    override val zenMode: StateFlow<ZenMode?>
        get() = mutableZenMode.asStateFlow()

    init {
        updateNotificationPolicy()
    }

    fun updateNotificationPolicy(policy: NotificationManager.Policy?) {
        mutableNotificationPolicy.value = policy
    }

    fun updateZenMode(zenMode: ZenMode?) {
        mutableZenMode.value = zenMode
    }
}

fun FakeNotificationsSoundPolicyRepository.updateNotificationPolicy(
    priorityCategories: Int = 0,
    priorityCallSenders: Int = NotificationManager.Policy.PRIORITY_SENDERS_ANY,
    priorityMessageSenders: Int = NotificationManager.Policy.CONVERSATION_SENDERS_NONE,
    suppressedVisualEffects: Int = NotificationManager.Policy.SUPPRESSED_EFFECTS_UNSET,
    state: Int = NotificationManager.Policy.STATE_UNSET,
    priorityConversationSenders: Int = NotificationManager.Policy.CONVERSATION_SENDERS_NONE,
) =
    updateNotificationPolicy(
        NotificationManager.Policy(
            priorityCategories,
            priorityCallSenders,
            priorityMessageSenders,
            suppressedVisualEffects,
            state,
            priorityConversationSenders,
        )
    )
