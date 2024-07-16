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

package com.android.systemui.statusbar.notification.row

import android.app.Flags
import android.app.Notification
import android.app.Notification.MessagingStyle
import android.app.Person
import android.content.Context
import android.graphics.drawable.Icon
import android.util.Log
import android.view.LayoutInflater
import com.android.app.tracing.traceSection
import com.android.internal.R
import com.android.internal.widget.MessagingMessage
import com.android.internal.widget.PeopleHelper
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_SINGLE_LINE
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ConversationAvatar
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ConversationData
import com.android.systemui.statusbar.notification.row.ui.viewmodel.FacePile
import com.android.systemui.statusbar.notification.row.ui.viewmodel.SingleIcon
import com.android.systemui.statusbar.notification.row.ui.viewmodel.SingleLineViewModel

/** The inflater of SingleLineViewModel and SingleLineViewHolder */
internal object SingleLineViewInflater {
    const val TAG = "SingleLineViewInflater"

    /**
     * Inflate an instance of SingleLineViewModel.
     *
     * @param notification the notification to show
     * @param messagingStyle the MessagingStyle information is only provided for conversation
     *   notification, not for legacy messaging notifications
     * @param builder the recovered Notification Builder
     * @param systemUiContext the context of Android System UI
     * @return the inflated SingleLineViewModel
     */
    @JvmStatic
    fun inflateSingleLineViewModel(
        notification: Notification,
        messagingStyle: MessagingStyle?,
        builder: Notification.Builder,
        systemUiContext: Context,
    ): SingleLineViewModel {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) {
            return SingleLineViewModel(null, null, null)
        }
        peopleHelper.init(systemUiContext)
        var titleText = HybridGroupManager.resolveTitle(notification)
        var contentText = HybridGroupManager.resolveText(notification)

        if (messagingStyle == null) {
            return SingleLineViewModel(
                titleText = titleText,
                contentText = contentText,
                conversationData = null,
            )
        }

        val isGroupConversation = messagingStyle.isGroupConversation

        val conversationTextData = messagingStyle.loadConversationTextData(systemUiContext)
        if (conversationTextData?.conversationTitle?.isNotEmpty() == true) {
            titleText = conversationTextData.conversationTitle
        }
        if (conversationTextData?.conversationText?.isNotEmpty() == true) {
            contentText = conversationTextData.conversationText
        }

        val conversationAvatar =
            messagingStyle.loadConversationAvatar(
                notification = notification,
                isGroupConversation = isGroupConversation,
                builder = builder,
                systemUiContext = systemUiContext
            )

        val conversationData =
            ConversationData(
                // We don't show the sender's name for one-to-one conversation
                conversationSenderName =
                    if (isGroupConversation) conversationTextData?.senderName else null,
                avatar = conversationAvatar
            )

        return SingleLineViewModel(
            titleText = titleText,
            contentText = contentText,
            conversationData = conversationData,
        )
    }

    /** load conversation text data from the MessagingStyle of conversation notifications */
    private fun MessagingStyle.loadConversationTextData(
        systemUiContext: Context
    ): ConversationTextData? {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) {
            return null
        }
        var conversationText: CharSequence?

        if (messages.isEmpty()) {
            return null
        }

        // load the conversation text
        val lastMessage = messages[messages.lastIndex]
        conversationText = lastMessage.text
        if (conversationText == null && lastMessage.isImageMessage()) {
            conversationText = findBackUpConversationText(lastMessage, systemUiContext)
        }

        // load the sender's name to display
        val name = lastMessage.senderPerson?.name
        val senderName =
            systemUiContext.resources.getString(
                R.string.conversation_single_line_name_display,
                if (Flags.cleanUpSpansAndNewLines()) name?.toString() else name
            )

        // We need to find back-up values for those texts if they are needed and empty
        return ConversationTextData(
            conversationTitle = conversationTitle
                    ?: findBackUpConversationTitle(senderName, systemUiContext),
            conversationText = conversationText,
            senderName = senderName,
        )
    }

    private fun MessagingStyle.Message.isImageMessage(): Boolean = MessagingMessage.hasImage(this)

    /** find a back-up conversation title when the conversation title is null. */
    private fun MessagingStyle.findBackUpConversationTitle(
        senderName: CharSequence?,
        systemUiContext: Context,
    ): CharSequence {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) {
            return ""
        }
        return if (isGroupConversation) {
            systemUiContext.resources.getString(R.string.conversation_title_fallback_group_chat)
        } else {
            // Is one-to-one, let's try to use the last sender's name
            // The last back-up is the value of resource: conversation_title_fallback_one_to_one
            senderName
                ?: systemUiContext.resources.getString(
                    R.string.conversation_title_fallback_one_to_one
                )
        }
    }

    /**
     * find a back-up conversation text when the conversation has null text and is image message.
     */
    private fun findBackUpConversationText(
        message: MessagingStyle.Message,
        context: Context,
    ): CharSequence? {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) {
            return null
        }
        // If the message is not an image message, just return empty, the back-up text for showing
        // will be SingleLineViewModel.contentText
        if (!message.isImageMessage()) return null
        // If is image message, return a placeholder
        return context.resources.getString(R.string.conversation_single_line_image_placeholder)
    }

    /**
     * The text data that we load from a conversation notification to show in the single-line views.
     *
     * Group conversation single-line view should be formatted as:
     * [conversationTitle, senderName, conversationText]
     *
     * One-to-one single-line view should be formatted as:
     * [conversationTitle (which is equal to the senderName), conversationText]
     *
     * @property conversationTitle the title of the conversation, not necessarily the title of the
     *   notification row. conversationTitle is non-null, though may be empty, in which case we need
     *   to show the notification title instead.
     * @property conversationText the text content of the conversation, single-line will use the
     *   notification's text when conversationText is null
     * @property senderName the sender's name to be shown in the row when needed. senderName can be
     *   null
     */
    data class ConversationTextData(
        val conversationTitle: CharSequence,
        val conversationText: CharSequence?,
        val senderName: CharSequence?,
    )

    private fun groupMessages(
        messages: List<MessagingStyle.Message>,
        historicMessages: List<MessagingStyle.Message>,
    ): List<MutableList<MessagingStyle.Message>> {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) {
            return listOf()
        }
        if (messages.isEmpty() && historicMessages.isEmpty()) return listOf()
        var currentGroup: MutableList<MessagingStyle.Message>? = null
        var currentSenderKey: CharSequence? = null
        val groups = mutableListOf<MutableList<MessagingStyle.Message>>()
        val histSize = historicMessages.size
        for (i in 0 until (histSize + messages.size)) {
            val message = if (i < histSize) historicMessages[i] else messages[i - histSize]

            val sender = message.senderPerson
            val senderKey = sender?.getKeyOrName()
            val isNewGroup = (currentGroup == null) || senderKey != currentSenderKey
            if (isNewGroup) {
                currentGroup = mutableListOf()
                groups.add(currentGroup)
                currentSenderKey = senderKey
            }
            currentGroup?.add(message)
        }
        return groups
    }

    private fun MessagingStyle.loadConversationAvatar(
        builder: Notification.Builder,
        notification: Notification,
        isGroupConversation: Boolean,
        systemUiContext: Context,
    ): ConversationAvatar {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) {
            return SingleIcon(null)
        }
        val userKey = user.getKeyOrName()
        var conversationIcon: Icon? = shortcutIcon
        var conversationText: CharSequence? = conversationTitle

        val groups = groupMessages(messages, historicMessages)
        val uniqueNames = peopleHelper.mapUniqueNamesToPrefixWithGroupList(groups)

        if (!isGroupConversation) {
            // Conversation is one-to-one, load the single icon
            // Let's resolve the icon / text from the last sender
            for (i in messages.lastIndex downTo 0) {
                val message = messages[i]
                val sender = message.senderPerson
                val senderKey = sender?.getKeyOrName()
                if ((sender != null && senderKey != userKey) || i == 0) {
                    if (conversationText.isNullOrEmpty()) {
                        // We use the senderName as header text if no conversation title is provided
                        // (This usually happens for most 1:1 conversations)
                        conversationText = sender?.name ?: ""
                    }
                    if (conversationIcon == null) {
                        var avatarIcon = sender?.icon
                        if (avatarIcon == null) {
                            avatarIcon = builder.getDefaultAvatar(name = conversationText)
                        }
                        conversationIcon = avatarIcon
                    }
                    break
                }
            }
        }

        if (conversationIcon == null) {
            conversationIcon = notification.getLargeIcon()
        }

        // If is one-to-one or the conversation has an icon, return a single icon
        if (!isGroupConversation || conversationIcon != null) {
            return SingleIcon(conversationIcon?.loadDrawable(systemUiContext))
        }

        // Otherwise, let's find the two last conversations to build a face pile:
        var secondLastIcon: Icon? = null
        var lastIcon: Icon? = null
        var lastKey: CharSequence? = null

        for (i in groups.lastIndex downTo 0) {
            val message = groups[i][0]
            val sender = message.senderPerson ?: user
            val senderKey = sender.getKeyOrName()
            val notUser = senderKey != userKey
            val notIncluded = senderKey != lastKey

            if ((notUser && notIncluded) || (i == 0 && lastKey == null)) {
                if (lastIcon == null) {
                    lastIcon =
                        sender.icon
                            ?: builder.getDefaultAvatar(
                                name = sender.name,
                                uniqueNames = uniqueNames
                            )
                    lastKey = senderKey
                } else {
                    secondLastIcon =
                        sender.icon
                            ?: builder.getDefaultAvatar(
                                name = sender.name,
                                uniqueNames = uniqueNames
                            )
                    break
                }
            }
        }

        if (lastIcon == null) {
            lastIcon = builder.getDefaultAvatar(name = "")
        }

        if (secondLastIcon == null) {
            secondLastIcon = builder.getDefaultAvatar(name = "")
        }

        return FacePile(
            topIconDrawable = secondLastIcon.loadDrawable(systemUiContext),
            bottomIconDrawable = lastIcon.loadDrawable(systemUiContext),
            bottomBackgroundColor = builder.getBackgroundColor(/* isHeader = */ false),
        )
    }

    @JvmStatic
    fun inflateSingleLineViewHolder(
        isConversation: Boolean,
        reinflateFlags: Int,
        entry: NotificationEntry,
        context: Context,
        logger: NotificationRowContentBinderLogger,
    ): HybridNotificationView? {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) return null
        if (reinflateFlags and FLAG_CONTENT_VIEW_SINGLE_LINE == 0) {
            return null
        }

        logger.logInflateSingleLine(entry, reinflateFlags, isConversation)
        logger.logAsyncTaskProgress(entry, "inflating single-line content view")

        var view: HybridNotificationView? = null

        traceSection("SingleLineViewInflater#inflateSingleLineView") {
            val inflater = LayoutInflater.from(context)
            val layoutRes: Int =
                if (isConversation)
                    com.android.systemui.res.R.layout.hybrid_conversation_notification
                else com.android.systemui.res.R.layout.hybrid_notification
            view = inflater.inflate(layoutRes, /* root = */ null) as HybridNotificationView
            if (view == null) {
                Log.wtf(TAG, "Single-line view inflation result is null for entry: ${entry.logKey}")
            }
        }
        return view
    }

    private fun Notification.Builder.getDefaultAvatar(
        name: CharSequence?,
        uniqueNames: PeopleHelper.NameToPrefixMap? = null
    ): Icon {
        val layoutColor = getSmallIconColor(/* isHeader = */ false)
        if (!name.isNullOrEmpty()) {
            val symbol = uniqueNames?.getPrefix(name) ?: ""
            return peopleHelper.createAvatarSymbol(
                /* name = */ name,
                /* symbol = */ symbol,
                /* layoutColor = */ layoutColor
            )
        }
        // If name is null, create default avatar with background color
        // TODO(b/319829062): Investigate caching default icon for color
        return peopleHelper.createAvatarSymbol(/* name = */ "", /* symbol = */ "", layoutColor)
    }

    private fun Person.getKeyOrName(): CharSequence? = if (key == null) name else key

    private val peopleHelper = PeopleHelper()
}
