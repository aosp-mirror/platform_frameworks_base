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
import android.view.ViewGroup
import com.android.internal.widget.ConversationLayout
import com.android.internal.widget.MessagingLinearLayout
import com.android.systemui.R
import com.android.systemui.statusbar.TransformableView
import com.android.systemui.statusbar.ViewTransformationHelper
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.TransformState
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.HybridNotificationView

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
    private var conversationIcon: View? = null
    private var conversationBadge: View? = null
    private var expandButton: View? = null
    private lateinit var expandButtonContainer: View
    private lateinit var imageMessageContainer: ViewGroup
    private var messagingLinearLayout: MessagingLinearLayout? = null

    init {
        conversationLayout = view as ConversationLayout
        minHeightWithActions = NotificationUtils.getFontScaledHeight(ctx,
                R.dimen.notification_messaging_actions_min_height)
    }

    private fun resolveViews() {
        messagingLinearLayout = conversationLayout.messagingLinearLayout
        imageMessageContainer = conversationLayout.imageMessageContainer
        conversationIcon = conversationLayout.requireViewById(
                com.android.internal.R.id.conversation_icon)
        conversationBadge = conversationLayout.requireViewById(
                com.android.internal.R.id.conversation_icon_badge)
        expandButton = conversationLayout.requireViewById(
                com.android.internal.R.id.expand_button)
        expandButtonContainer = conversationLayout.requireViewById(
                com.android.internal.R.id.expand_button_container)
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
        messagingLinearLayout?.let {
            mTransformationHelper.addTransformedView(it.id, it)
        }

        // Let's ignore the image message container since that is transforming as part of the
        // messages already
        mTransformationHelper.setCustomTransformation(
                object : ViewTransformationHelper.CustomTransformation() {
            override fun transformTo(ownState: TransformState,
                                     otherView: TransformableView,
                                     transformationAmount: Float): Boolean {
                if (otherView is HybridNotificationView) {
                    return false
                }
                // we're hidden by default by the transformState
                ownState.ensureVisible();
                // Let's do nothing otherwise, this is already handled by the messages
                return true
            }

            override fun transformFrom(ownState: TransformState,
                                       otherView: TransformableView,
                                       transformationAmount: Float): Boolean {
                if (otherView is HybridNotificationView) {
                    return false
                }
                // we're hidden by default by the transformState
                ownState.ensureVisible();
                // Let's do nothing otherwise, this is already handled by the messages
                return true
            }
        }, imageMessageContainer.id)

        conversationIcon?.let {
            mTransformationHelper.addViewTransformingToSimilar(it.id, it)
        }
        conversationBadge?.let {
            mTransformationHelper.addViewTransformingToSimilar(it.id, it)
        }
        expandButton?.let {
            mTransformationHelper.addViewTransformingToSimilar(it.id, it)
        }
    }

    override fun setRemoteInputVisible(visible: Boolean) {
        conversationLayout.showHistoricMessages(visible)
    }

    override fun updateExpandability(expandable: Boolean, onClickListener: View.OnClickListener?) {
        conversationLayout.updateExpandability(expandable, onClickListener)
    }

    override fun disallowSingleClick(x: Float, y: Float): Boolean {
        if (expandButtonContainer.visibility == View.VISIBLE
                && isOnView(expandButtonContainer, x, y)) {
            return true
        }
        return super.disallowSingleClick(x, y)
    }

    override fun getMinLayoutHeight(): Int {
        if (mActionsContainer != null && mActionsContainer.visibility != View.GONE) {
            return minHeightWithActions
        } else {
            return super.getMinLayoutHeight()
        }
    }
}
