/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.inflation

import android.app.Notification
import android.app.RemoteInput
import android.graphics.drawable.Icon
import android.text.TextUtils
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation

/**
 * An immutable object which contains minimal state extracted from an entry that represents state
 * which can change without a direct app update (e.g. with a ranking update).
 * Diffing two entries determines if view re-inflation is needed.
 */
class NotifUiAdjustment internal constructor(
    val key: String,
    val smartActions: List<Notification.Action>,
    val smartReplies: List<CharSequence>,
    val isConversation: Boolean,
    val isSnoozeEnabled: Boolean,
    val isMinimized: Boolean,
    val needsRedaction: Boolean,
    val isChildInGroup: Boolean,
) {
    companion object {
        @JvmStatic
        fun needReinflate(
            oldAdjustment: NotifUiAdjustment,
            newAdjustment: NotifUiAdjustment
        ): Boolean = when {
            oldAdjustment === newAdjustment -> false
            oldAdjustment.isConversation != newAdjustment.isConversation -> true
            oldAdjustment.isSnoozeEnabled != newAdjustment.isSnoozeEnabled -> true
            oldAdjustment.isMinimized != newAdjustment.isMinimized -> true
            oldAdjustment.needsRedaction != newAdjustment.needsRedaction -> true
            areDifferent(oldAdjustment.smartActions, newAdjustment.smartActions) -> true
            newAdjustment.smartReplies != oldAdjustment.smartReplies -> true
            // TODO(b/217799515): Here we decide whether to re-inflate the row on every group-status
            //  change if we want to keep the single-line view, the following line should be:
            //  !oldAdjustment.isChildInGroup && newAdjustment.isChildInGroup -> true
            AsyncHybridViewInflation.isEnabled &&
                    oldAdjustment.isChildInGroup != newAdjustment.isChildInGroup -> true
            else -> false
        }

        private fun areDifferent(
            first: List<Notification.Action>,
            second: List<Notification.Action>
        ): Boolean = when {
            first === second -> false
            first.size != second.size -> true
            else -> first.asSequence().zip(second.asSequence()).any {
                (!TextUtils.equals(it.first.title, it.second.title)) ||
                    (areDifferent(it.first.getIcon(), it.second.getIcon())) ||
                    (it.first.actionIntent != it.second.actionIntent) ||
                    (areDifferent(it.first.remoteInputs, it.second.remoteInputs))
            }
        }

        private fun areDifferent(first: Icon?, second: Icon?): Boolean = when {
            first === second -> false
            first == null || second == null -> true
            else -> !first.sameAs(second)
        }

        private fun areDifferent(
            first: Array<RemoteInput>?,
            second: Array<RemoteInput>?
        ): Boolean = when {
            first === second -> false
            first == null || second == null -> true
            first.size != second.size -> true
            else -> first.asSequence().zip(second.asSequence()).any {
                (!TextUtils.equals(it.first.label, it.second.label)) ||
                    (areDifferent(it.first.choices, it.second.choices))
            }
        }

        private fun areDifferent(
            first: Array<CharSequence>?,
            second: Array<CharSequence>?
        ): Boolean = when {
            first === second -> false
            first == null || second == null -> true
            first.size != second.size -> true
            else -> first.asSequence().zip(second.asSequence()).any {
                !TextUtils.equals(it.first, it.second)
            }
        }
    }
}
