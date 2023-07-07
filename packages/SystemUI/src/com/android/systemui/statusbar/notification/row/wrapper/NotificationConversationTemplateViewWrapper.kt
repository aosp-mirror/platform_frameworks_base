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
import android.graphics.drawable.AnimatedImageDrawable
import android.view.View
import android.view.ViewGroup
import com.android.internal.widget.CachingIconView
import com.android.internal.widget.ConversationLayout
import com.android.internal.widget.MessagingGroup
import com.android.internal.widget.MessagingImageMessage
import com.android.internal.widget.MessagingLinearLayout
import com.android.systemui.R
import com.android.systemui.statusbar.notification.NotificationFadeAware
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.wrapper.NotificationMessagingTemplateViewWrapper.setCustomImageMessageTransform
import com.android.systemui.util.children

/**
 * Wraps a notification containing a conversation template
 */
class NotificationConversationTemplateViewWrapper constructor(
    ctx: Context,
    view: View,
    row: ExpandableNotificationRow
) : NotificationTemplateViewWrapper(ctx, view, row) {

    private val minHeightWithActions: Int = NotificationUtils.getFontScaledHeight(
            ctx,
            R.dimen.notification_messaging_actions_min_height
    )
    private val conversationLayout: ConversationLayout = view as ConversationLayout

    private lateinit var conversationIconContainer: View
    private lateinit var conversationIconView: CachingIconView
    private lateinit var conversationBadgeBg: View
    private lateinit var expandBtn: View
    private lateinit var expandBtnContainer: View
    private lateinit var imageMessageContainer: ViewGroup
    private lateinit var messageContainers: ArrayList<MessagingGroup>
    private lateinit var messagingLinearLayout: MessagingLinearLayout
    private lateinit var conversationTitleView: View
    private lateinit var importanceRing: View
    private lateinit var appName: View
    private var facePileBottomBg: View? = null
    private var facePileBottom: View? = null
    private var facePileTop: View? = null

    private fun resolveViews() {
        messagingLinearLayout = conversationLayout.messagingLinearLayout
        imageMessageContainer = conversationLayout.imageMessageContainer
        messageContainers = conversationLayout.messagingGroups
        with(conversationLayout) {
            conversationIconContainer =
                    requireViewById(com.android.internal.R.id.conversation_icon_container)
            conversationIconView = requireViewById(com.android.internal.R.id.conversation_icon)
            conversationBadgeBg =
                    requireViewById(com.android.internal.R.id.conversation_icon_badge_bg)
            expandBtn = requireViewById(com.android.internal.R.id.expand_button)
            expandBtnContainer = requireViewById(com.android.internal.R.id.expand_button_container)
            importanceRing = requireViewById(com.android.internal.R.id.conversation_icon_badge_ring)
            appName = requireViewById(com.android.internal.R.id.app_name_text)
            conversationTitleView = requireViewById(com.android.internal.R.id.conversation_text)
            facePileTop = findViewById(com.android.internal.R.id.conversation_face_pile_top)
            facePileBottom = findViewById(com.android.internal.R.id.conversation_face_pile_bottom)
            facePileBottomBg =
                    findViewById(com.android.internal.R.id.conversation_face_pile_bottom_background)
        }
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

        mTransformationHelper.addTransformedView(TRANSFORMING_VIEW_TITLE, conversationTitleView)
        addTransformedViews(
                messagingLinearLayout,
                appName
        )

        setCustomImageMessageTransform(mTransformationHelper, imageMessageContainer)

        addViewsTransformingToSimilar(
                conversationIconView,
                conversationBadgeBg,
                expandBtn,
                importanceRing,
                facePileTop,
                facePileBottom,
                facePileBottomBg
        )
    }

    override fun getShelfTransformationTarget(): View? =
            if (conversationLayout.isImportantConversation)
                if (conversationIconView.visibility != View.GONE)
                    conversationIconView
                else
                    // A notification with a fallback icon was set to important. Currently
                    // the transformation doesn't work for these and needs to be fixed.
                    // In the meantime those are using the icon.
                    super.getShelfTransformationTarget()
            else
                super.getShelfTransformationTarget()

    override fun setRemoteInputVisible(visible: Boolean) =
            conversationLayout.showHistoricMessages(visible)

    override fun updateExpandability(
        expandable: Boolean,
        onClickListener: View.OnClickListener,
        requestLayout: Boolean
    ) = conversationLayout.updateExpandability(expandable, onClickListener)

    override fun disallowSingleClick(x: Float, y: Float): Boolean {
        val isOnExpandButton = expandBtnContainer.visibility == View.VISIBLE &&
                isOnView(expandBtnContainer, x, y)
        return isOnExpandButton || super.disallowSingleClick(x, y)
    }

    override fun getMinLayoutHeight(): Int =
            if (mActionsContainer != null && mActionsContainer.visibility != View.GONE)
                minHeightWithActions
            else
                super.getMinLayoutHeight()

    override fun setNotificationFaded(faded: Boolean) {
        // Do not call super
        NotificationFadeAware.setLayerTypeForFaded(expandBtn, faded)
        NotificationFadeAware.setLayerTypeForFaded(conversationIconContainer, faded)
    }

    // Starts or stops the animations in any drawables contained in this Conversation Notification.
    override fun setAnimationsRunning(running: Boolean) {
        // We apply to both the child message containers in a conversation group,
        // and the top level image message container.
        val containers = messageContainers.asSequence().map { it.messageContainer } +
                sequenceOf(imageMessageContainer)
        val drawables =
                containers
                        .flatMap { it.children }
                        .mapNotNull { child ->
                            (child as? MessagingImageMessage)?.let { imageMessage ->
                                imageMessage.drawable as? AnimatedImageDrawable
                            }
                        }
        drawables.toSet().forEach {
            when {
                running -> it.start()
                !running -> it.stop()
            }
        }
    }
}
