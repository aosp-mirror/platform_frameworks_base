/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.Notification
import com.android.systemui.statusbar.policy.SmartReplyView.SmartActions
import com.android.systemui.statusbar.policy.SmartReplyView.SmartReplies

/**
 * A storage for smart replies, smart actions, and related state
 */
class InflatedSmartReplyState internal constructor(
    val smartReplies: SmartReplies?,
    val smartActions: SmartActions?,
    val suppressedActions: SuppressedActions?,
    val hasPhishingAction: Boolean
) {
    val smartRepliesList: List<CharSequence>
        get() = smartReplies?.choices ?: emptyList()
    val smartActionsList: List<Notification.Action>
        get() = smartActions?.actions ?: emptyList()
    val suppressedActionIndices: List<Int>
        get() = suppressedActions?.suppressedActionIndices ?: emptyList()

    /**
     * Data class for standard actions suppressed by the smart actions.
     */
    class SuppressedActions(val suppressedActionIndices: List<Int>)
}