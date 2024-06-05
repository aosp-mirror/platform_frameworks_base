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

import android.app.Notification
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import androidx.core.graphics.drawable.toBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ConversationAvatar
import com.android.systemui.statusbar.notification.row.ui.viewmodel.FacePile
import com.android.systemui.statusbar.notification.row.ui.viewmodel.SingleIcon
import com.android.systemui.statusbar.notification.row.ui.viewmodel.SingleLineViewModel
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableFlags(AsyncHybridViewInflation.FLAG_NAME)
class SingleLineViewInflaterTest : SysuiTestCase() {
    private lateinit var helper: NotificationTestHelper
    // Non-group MessagingStyles only have firstSender
    private lateinit var firstSender: Person
    private lateinit var lastSender: Person
    private lateinit var firstSenderIcon: Icon
    private lateinit var lastSenderIcon: Icon
    private var firstSenderIconDrawable: Drawable? = null
    private var lastSenderIconDrawable: Drawable? = null
    private val currentUser: Person? = null

    private companion object {
        const val FIRST_SENDER_NAME = "First Sender"
        const val LAST_SENDER_NAME = "Second Sender"
        const val LAST_MESSAGE = "How about lunch?"

        const val CONVERSATION_TITLE = "The Sender Family"
        const val CONTENT_TITLE = "A Cool Group"
        const val CONTENT_TEXT = "This is an amazing group chat"

        const val SHORTCUT_ID = "Shortcut"
    }

    @Before
    fun setUp() {
        helper = NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))
        firstSenderIcon = Icon.createWithBitmap(getBitmap(context, R.drawable.ic_person))
        firstSenderIconDrawable = firstSenderIcon.loadDrawable(context)
        lastSenderIcon =
            Icon.createWithBitmap(
                getBitmap(context, com.android.internal.R.drawable.ic_account_circle)
            )
        lastSenderIconDrawable = lastSenderIcon.loadDrawable(context)
        firstSender = Person.Builder().setName(FIRST_SENDER_NAME).setIcon(firstSenderIcon).build()
        lastSender = Person.Builder().setName(LAST_SENDER_NAME).setIcon(lastSenderIcon).build()
    }

    @Test
    fun createViewModelForNonConversationSingleLineView() {
        // Given: a non-conversation notification
        val notificationType = NonMessaging()
        val notification = getNotification(NonMessaging())

        // When: inflate the SingleLineViewModel
        val singleLineViewModel = notification.makeSingleLineViewModel(notificationType)

        // Then: the inflated SingleLineViewModel should be as expected
        // conversationData: null, because it's not a conversation notification
        assertEquals(SingleLineViewModel(CONTENT_TITLE, CONTENT_TEXT, null), singleLineViewModel)
    }

    @Test
    fun createViewModelForNonGroupConversationNotification() {
        // Given: a non-group conversation notification
        val notificationType = OneToOneConversation()
        val notification = getNotification(notificationType)

        // When: inflate the SingleLineViewModel
        val singleLineViewModel = notification.makeSingleLineViewModel(notificationType)

        // Then: the inflated SingleLineViewModel should be as expected
        // titleText: Notification.ConversationTitle
        // contentText: the last message text
        // conversationSenderName: null, because it's not a group conversation
        // conversationData.avatar: a single icon of the last sender
        assertEquals(CONVERSATION_TITLE, singleLineViewModel.titleText)
        assertEquals(LAST_MESSAGE, singleLineViewModel.contentText)
        assertNull(
            singleLineViewModel.conversationData?.conversationSenderName,
            "Sender name should be null for one-on-one conversation"
        )
        assertTrue {
            singleLineViewModel.conversationData
                ?.avatar
                ?.equalsTo(SingleIcon(firstSenderIcon.loadDrawable(context))) == true
        }
    }

    @Test
    fun createViewModelForNonGroupLegacyMessagingStyleNotification() {
        // Given: a non-group legacy messaging style notification
        val notificationType = LegacyMessaging()
        val notification = getNotification(notificationType)

        // When: inflate the SingleLineViewModel
        val singleLineViewModel = notification.makeSingleLineViewModel(notificationType)

        // Then: the inflated SingleLineViewModel should be as expected
        // titleText: CONVERSATION_TITLE: SENDER_NAME
        // contentText: the last message text
        // conversationData: null, because it's not a conversation notification
        assertEquals("$CONVERSATION_TITLE: $FIRST_SENDER_NAME", singleLineViewModel.titleText)
        assertEquals(LAST_MESSAGE, singleLineViewModel.contentText)
        assertNull(
            singleLineViewModel.conversationData,
            "conversationData should be null for legacy messaging conversation"
        )
    }

    @Test
    fun createViewModelForGroupLegacyMessagingStyleNotification() {
        // Given: a non-group legacy messaging style notification
        val notificationType = LegacyMessagingGroup()
        val notification = getNotification(notificationType)

        // When: inflate the SingleLineViewModel
        val singleLineViewModel = notification.makeSingleLineViewModel(notificationType)

        // Then: the inflated SingleLineViewModel should be as expected
        // titleText: CONVERSATION_TITLE: LAST_SENDER_NAME
        // contentText: the last message text
        // conversationData: null, because it's not a conversation notification
        assertEquals("$CONVERSATION_TITLE: $LAST_SENDER_NAME", singleLineViewModel.titleText)
        assertEquals(LAST_MESSAGE, singleLineViewModel.contentText)
        assertNull(
            singleLineViewModel.conversationData,
            "conversationData should be null for legacy messaging conversation"
        )
    }

    @Test
    fun createViewModelForNonGroupConversationNotificationWithShortcutIcon() {
        // Given: a non-group conversation notification with a shortcut icon
        val shortcutIcon =
            Icon.createWithResource(context, com.android.internal.R.drawable.ic_account_circle)
        val notificationType = OneToOneConversation(shortcutIcon = shortcutIcon)
        val notification = getNotification(notificationType)

        // When: inflate the SingleLineViewModel
        val singleLineViewModel = notification.makeSingleLineViewModel(notificationType)

        // Then: the inflated SingleLineViewModel should be expected
        // titleText: Notification.ConversationTitle
        // contentText: the last message text
        // conversationSenderName: null, because it's not a group conversation
        // conversationData.avatar: a single icon of the shortcut icon
        assertEquals(CONVERSATION_TITLE, singleLineViewModel.titleText)
        assertEquals(LAST_MESSAGE, singleLineViewModel.contentText)
        assertNull(
            singleLineViewModel.conversationData?.conversationSenderName,
            "Sender name should be null for one-on-one conversation"
        )
        assertTrue {
            singleLineViewModel.conversationData
                ?.avatar
                ?.equalsTo(SingleIcon(shortcutIcon.loadDrawable(context))) == true
        }
    }

    @Test
    fun createViewModelForGroupConversationNotificationWithLargeIcon() {
        // Given: a group conversation notification with a large icon
        val largeIcon =
            Icon.createWithResource(context, com.android.internal.R.drawable.ic_account_circle)
        val notificationType = GroupConversation(largeIcon = largeIcon)
        val notification = getNotification(notificationType)

        // When: inflate the SingleLineViewModel
        val singleLineViewModel = notification.makeSingleLineViewModel(notificationType)

        // Then: the inflated SingleLineViewModel should be expected
        // titleText: Notification.ConversationTitle
        // contentText: the last message text
        // conversationSenderName: the last non-user sender's name
        // conversationData.avatar: a single icon
        assertEquals(CONVERSATION_TITLE, singleLineViewModel.titleText)
        assertEquals(LAST_MESSAGE, singleLineViewModel.contentText)
        assertEquals(
            context.resources.getString(
                com.android.internal.R.string.conversation_single_line_name_display,
                LAST_SENDER_NAME
            ),
            singleLineViewModel.conversationData?.conversationSenderName
        )
        assertTrue {
            singleLineViewModel.conversationData
                ?.avatar
                ?.equalsTo(SingleIcon(largeIcon.loadDrawable(context))) == true
        }
    }

    @Test
    fun createViewModelForGroupConversationWithNoIcon() {
        // Given: a group conversation notification
        val notificationType = GroupConversation()
        val notification = getNotification(notificationType)

        // When: inflate the SingleLineViewModel
        val singleLineViewModel = notification.makeSingleLineViewModel(notificationType)

        // Then: the inflated SingleLineViewModel should be expected
        // titleText: Notification.ConversationTitle
        // contentText: the last message text
        // conversationSenderName: the last non-user sender's name
        // conversationData.avatar: a face-pile consists the last sender's icon
        assertEquals(CONVERSATION_TITLE, singleLineViewModel.titleText)
        assertEquals(LAST_MESSAGE, singleLineViewModel.contentText)
        assertEquals(
            context.resources.getString(
                com.android.internal.R.string.conversation_single_line_name_display,
                LAST_SENDER_NAME
            ),
            singleLineViewModel.conversationData?.conversationSenderName
        )

        val backgroundColor =
            Notification.Builder.recoverBuilder(context, notification)
                .getBackgroundColor(/* isHeader = */ false)
        assertTrue {
            singleLineViewModel.conversationData
                ?.avatar
                ?.equalsTo(
                    FacePile(
                        firstSenderIconDrawable,
                        lastSenderIconDrawable,
                        backgroundColor,
                    )
                ) == true
        }
    }

    sealed class NotificationType(val largeIcon: Icon? = null)

    class NonMessaging(largeIcon: Icon? = null) : NotificationType(largeIcon)

    class LegacyMessaging(largeIcon: Icon? = null) : NotificationType(largeIcon)

    class LegacyMessagingGroup(largeIcon: Icon? = null) : NotificationType(largeIcon)

    class OneToOneConversation(largeIcon: Icon? = null, val shortcutIcon: Icon? = null) :
        NotificationType(largeIcon)

    class GroupConversation(largeIcon: Icon? = null) : NotificationType(largeIcon)

    private fun getNotification(type: NotificationType): Notification {
        val notificationBuilder: Notification.Builder =
            Notification.Builder(mContext, "channelId")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle(CONTENT_TITLE)
                .setContentText(CONTENT_TEXT)
                .setLargeIcon(type.largeIcon)

        val user = Person.Builder().setName("User").build()

        val buildMessagingStyle =
            Notification.MessagingStyle(user)
                .setConversationTitle(CONVERSATION_TITLE)
                .addMessage("Hi", 0, currentUser)

        return when (type) {
            is NonMessaging ->
                notificationBuilder
                    .setStyle(Notification.BigTextStyle().bigText("Big Text"))
                    .build()
            is LegacyMessaging -> {
                buildMessagingStyle
                    .addMessage("What's up?", 0, firstSender)
                    .addMessage("Not much", 0, currentUser)
                    .addMessage(LAST_MESSAGE, 0, firstSender)

                val notification = notificationBuilder.setStyle(buildMessagingStyle).build()

                assertNull(notification.shortcutId)
                notification
            }
            is LegacyMessagingGroup -> {
                buildMessagingStyle
                    .addMessage("What's up?", 0, firstSender)
                    .addMessage("Check out my new hover board!", 0, lastSender)
                    .setGroupConversation(true)
                    .addMessage(LAST_MESSAGE, 0, lastSender)

                val notification = notificationBuilder.setStyle(buildMessagingStyle).build()

                assertNull(notification.shortcutId)
                notification
            }
            is OneToOneConversation -> {
                buildMessagingStyle
                    .addMessage("What's up?", 0, firstSender)
                    .addMessage("Not much", 0, currentUser)
                    .addMessage(LAST_MESSAGE, 0, firstSender)
                    .setShortcutIcon(type.shortcutIcon)
                notificationBuilder.setShortcutId(SHORTCUT_ID).setStyle(buildMessagingStyle).build()
            }
            is GroupConversation -> {
                buildMessagingStyle
                    .addMessage("What's up?", 0, firstSender)
                    .addMessage("Check out my new hover board!", 0, lastSender)
                    .setGroupConversation(true)
                    .addMessage(LAST_MESSAGE, 0, lastSender)
                notificationBuilder.setShortcutId(SHORTCUT_ID).setStyle(buildMessagingStyle).build()
            }
        }
    }

    private fun Notification.makeSingleLineViewModel(type: NotificationType): SingleLineViewModel {
        val builder = Notification.Builder.recoverBuilder(context, this)

        // Validate the recovered builder has the right type of style
        val expectMessagingStyle =
            when (type) {
                is LegacyMessaging,
                is LegacyMessagingGroup,
                is OneToOneConversation,
                is GroupConversation -> true
                else -> false
            }
        if (expectMessagingStyle) {
            assertIs<Notification.MessagingStyle>(
                builder.style,
                "Notification style should be MessagingStyle"
            )
        } else {
            assertIsNot<Notification.MessagingStyle>(
                builder.style,
                message = "Notification style should not be MessagingStyle"
            )
        }

        // Inflate the SingleLineViewModel
        // Mock the behavior of NotificationRowContentBinder.doInBackground
        val messagingStyle = builder.getMessagingStyle()
        val isConversation = type is OneToOneConversation || type is GroupConversation
        return SingleLineViewInflater.inflateSingleLineViewModel(
            this,
            if (isConversation) messagingStyle else null,
            builder,
            context
        )
    }

    private fun Notification.Builder.getMessagingStyle(): Notification.MessagingStyle? {
        return style as? Notification.MessagingStyle
    }

    private fun getBitmap(context: Context, resId: Int): Bitmap {
        val largeIconDimension =
            context.resources.getDimension(R.dimen.conversation_single_line_avatar_size)
        val d = context.resources.getDrawable(resId)
        val b =
            Bitmap.createBitmap(
                largeIconDimension.toInt(),
                largeIconDimension.toInt(),
                Bitmap.Config.ARGB_8888
            )
        val c = Canvas(b)
        val paint = Paint()
        c.drawCircle(
            largeIconDimension / 2,
            largeIconDimension / 2,
            largeIconDimension.coerceAtMost(largeIconDimension) / 2,
            paint
        )
        d.setBounds(0, 0, largeIconDimension.toInt(), largeIconDimension.toInt())
        paint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))
        c.saveLayer(0F, 0F, largeIconDimension, largeIconDimension, paint, Canvas.ALL_SAVE_FLAG)
        d.draw(c)
        c.restore()
        return b
    }

    fun ConversationAvatar.equalsTo(other: ConversationAvatar?): Boolean =
        when {
            this === other -> true
            this is SingleIcon && other is SingleIcon -> equalsTo(other)
            this is FacePile && other is FacePile -> equalsTo(other)
            else -> false
        }

    private fun SingleIcon.equalsTo(other: SingleIcon): Boolean =
        iconDrawable?.equalsTo(other.iconDrawable) == true

    private fun FacePile.equalsTo(other: FacePile): Boolean =
        when {
            bottomBackgroundColor != other.bottomBackgroundColor -> false
            topIconDrawable?.equalsTo(other.topIconDrawable) != true -> false
            bottomIconDrawable?.equalsTo(other.bottomIconDrawable) != true -> false
            else -> true
        }

    fun Drawable.equalsTo(other: Drawable?): Boolean =
        when {
            this === other -> true
            this.pixelsEqualTo(other) -> true
            else -> false
        }

    private fun <T : Drawable> T.pixelsEqualTo(t: T?) =
        toBitmap().pixelsEqualTo(t?.toBitmap(), false)

    private fun Bitmap.pixelsEqualTo(otherBitmap: Bitmap?, shouldRecycle: Boolean = false) =
        otherBitmap?.let { other ->
            if (width == other.width && height == other.height) {
                val res = toPixels().contentEquals(other.toPixels())
                if (shouldRecycle) {
                    doRecycle().also { otherBitmap.doRecycle() }
                }
                res
            } else false
        }
            ?: kotlin.run { false }

    private fun Bitmap.toPixels() =
        IntArray(width * height).apply { getPixels(this, 0, width, 0, 0, width, height) }

    fun Bitmap.doRecycle() {
        if (!isRecycled) recycle()
    }
}
