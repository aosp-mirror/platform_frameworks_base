/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row.wrapper

import android.content.Context
import android.view.View

import com.android.internal.widget.ConversationLayout
import com.android.internal.widget.MessagingLinearLayout
import com.android.systemui.R
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow

/**
 * Wraps a notification containing a converation template
 */
class NotificationConversationTemplateViewWrapper constructor(
    ctx: Context,
    view: View,
    row: ExpandableNotificationRow
)
    : NotificationTemplateViewWrapper(ctx, view, row) {

    private val minHeightWithActions: Int
    private val conversationLayout: ConversationLayout
    private var messagingLinearLayout: MessagingLinearLayout? = null

    init {
        conversationLayout = view as ConversationLayout
        minHeightWithActions = NotificationUtils.getFontScaledHeight(ctx,
                R.dimen.notification_messaging_actions_min_height)
    }

    private fun resolveViews() {
        messagingLinearLayout = conversationLayout.messagingLinearLayout
    }

    override fun onContentUpdated(row: ExpandableNotificationRow) {
        // Reinspect the notification. Before the super call, because the super call also updates
        // the transformation types and we need to have our values set by then.
        resolveViews()
        super.onContentUpdated(row)
    }

    override fun updateTransformedTypes() {
        // This also clears the existing types
        super.updateTransformedTypes()
        if (messagingLinearLayout != null) {
            mTransformationHelper.addTransformedView(messagingLinearLayout!!.id,
                    messagingLinearLayout)
        }
    }

    override fun setRemoteInputVisible(visible: Boolean) {
        conversationLayout.showHistoricMessages(visible)
    }

    override fun updateExpandability(expandable: Boolean, onClickListener: View.OnClickListener?) {
        conversationLayout.updateExpandability(expandable, onClickListener)
    }

    override fun getMinLayoutHeight(): Int {
        if (mActionsContainer != null && mActionsContainer.visibility != View.GONE) {
            return minHeightWithActions
        } else {
            return super.getMinLayoutHeight()
        }
    }
}
