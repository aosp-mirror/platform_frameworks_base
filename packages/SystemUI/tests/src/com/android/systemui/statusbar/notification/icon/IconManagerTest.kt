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
 * limitations under the License.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.icon

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.Person
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.AuxiliaryPersistenceWrapperTest.Companion.any
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class IconManagerTest : SysuiTestCase() {
    companion object {
        private const val TEST_PACKAGE_NAME = "test"

        private const val TEST_UID = 0
    }

    private var id = 0
    private val context = InstrumentationRegistry.getTargetContext()

    @Mock private lateinit var shortcut: ShortcutInfo
    @Mock private lateinit var shortcutIc: Icon
    @Mock private lateinit var messageIc: Icon
    @Mock private lateinit var largeIc: Icon
    @Mock private lateinit var smallIc: Icon
    @Mock private lateinit var drawable: Drawable
    @Mock private lateinit var row: ExpandableNotificationRow

    @Mock private lateinit var notifCollection: CommonNotifCollection
    @Mock private lateinit var launcherApps: LauncherApps

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val mainContext = testScope.coroutineContext
    private val bgContext = testScope.backgroundScope.coroutineContext

    private val iconBuilder = IconBuilder(context)

    private lateinit var iconManager: IconManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(shortcutIc.loadDrawableAsUser(any(), anyInt())).thenReturn(drawable)
        `when`(messageIc.loadDrawableAsUser(any(), anyInt())).thenReturn(drawable)
        `when`(largeIc.loadDrawableAsUser(any(), anyInt())).thenReturn(drawable)
        `when`(smallIc.loadDrawableAsUser(any(), anyInt())).thenReturn(drawable)

        `when`(shortcut.icon).thenReturn(shortcutIc)
        `when`(launcherApps.getShortcutIcon(shortcut)).thenReturn(shortcutIc)

        iconManager =
            IconManager(
                notifCollection,
                launcherApps,
                iconBuilder,
                testScope,
                bgContext,
                mainContext,
            )
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun testCreateIcons_chipNotifIconFlagDisabled_statusBarChipIconIsNull() {
        val entry =
            notificationEntry(hasShortcut = true, hasMessageSenderIcon = true, hasLargeIcon = true)
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()

        assertThat(entry?.icons?.statusBarChipIcon).isNull()
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun testCreateIcons_chipNotifIconFlagEnabled_statusBarChipIconIsNull() {
        val entry =
            notificationEntry(hasShortcut = true, hasMessageSenderIcon = true, hasLargeIcon = true)
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()

        assertThat(entry?.icons?.statusBarChipIcon).isNotNull()
    }

    @Test
    fun testCreateIcons_importantConversation_shortcutIcon() {
        val entry =
            notificationEntry(hasShortcut = true, hasMessageSenderIcon = true, hasLargeIcon = true)
        entry?.channel?.isImportantConversation = true
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.statusBarIcon?.sourceIcon).isEqualTo(shortcutIc)
    }

    @Test
    fun testCreateIcons_importantConversation_messageIcon() {
        val entry =
            notificationEntry(hasShortcut = false, hasMessageSenderIcon = true, hasLargeIcon = true)
        entry?.channel?.isImportantConversation = true
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.statusBarIcon?.sourceIcon).isEqualTo(messageIc)
    }

    @Test
    fun testCreateIcons_importantConversation_largeIcon() {
        val entry =
            notificationEntry(
                hasShortcut = false,
                hasMessageSenderIcon = false,
                hasLargeIcon = true
            )
        entry?.channel?.isImportantConversation = true
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.statusBarIcon?.sourceIcon).isEqualTo(largeIc)
    }

    @Test
    fun testCreateIcons_importantConversation_smallIcon() {
        val entry =
            notificationEntry(
                hasShortcut = false,
                hasMessageSenderIcon = false,
                hasLargeIcon = false
            )
        entry?.channel?.isImportantConversation = true
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.statusBarIcon?.sourceIcon).isEqualTo(smallIc)
    }

    @Test
    fun testCreateIcons_importantConversationWithoutMessagingStyle() {
        val entry =
            notificationEntry(
                hasShortcut = true,
                hasMessageSenderIcon = true,
                useMessagingStyle = false,
                hasLargeIcon = true
            )
        entry?.channel?.isImportantConversation = true
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.statusBarIcon?.sourceIcon).isEqualTo(smallIc)
    }

    @Test
    fun testCreateIcons_notImportantConversation() {
        val entry =
            notificationEntry(hasShortcut = true, hasMessageSenderIcon = true, hasLargeIcon = true)
        entry?.let { iconManager.createIcons(it) }
        assertThat(entry?.icons?.statusBarIcon?.sourceIcon).isEqualTo(smallIc)
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun testCreateIcons_sensitiveImportantConversation() {
        val entry =
            notificationEntry(hasShortcut = true, hasMessageSenderIcon = true, hasLargeIcon = false)
        entry?.setSensitive(true, true)
        entry?.channel?.isImportantConversation = true
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.statusBarIcon?.sourceIcon).isEqualTo(shortcutIc)
        assertThat(entry?.icons?.statusBarChipIcon?.sourceIcon).isEqualTo(shortcutIc)
        assertThat(entry?.icons?.shelfIcon?.sourceIcon).isEqualTo(smallIc)
        assertThat(entry?.icons?.aodIcon?.sourceIcon).isEqualTo(smallIc)
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun testUpdateIcons_sensitiveImportantConversation() {
        val entry =
            notificationEntry(hasShortcut = true, hasMessageSenderIcon = true, hasLargeIcon = false)
        entry?.setSensitive(true, true)
        entry?.channel?.isImportantConversation = true
        entry?.let { iconManager.createIcons(it) }
        // Updating the icons after creation shouldn't break anything
        entry?.let { iconManager.updateIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.statusBarIcon?.sourceIcon).isEqualTo(shortcutIc)
        assertThat(entry?.icons?.statusBarChipIcon?.sourceIcon).isEqualTo(shortcutIc)
        assertThat(entry?.icons?.shelfIcon?.sourceIcon).isEqualTo(smallIc)
        assertThat(entry?.icons?.aodIcon?.sourceIcon).isEqualTo(smallIc)
    }

    @Test
    fun testUpdateIcons_sensitivityChange() {
        val entry =
            notificationEntry(hasShortcut = true, hasMessageSenderIcon = true, hasLargeIcon = false)
        entry?.channel?.isImportantConversation = true
        entry?.setSensitive(true, true)
        entry?.let { iconManager.createIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.aodIcon?.sourceIcon).isEqualTo(smallIc)
        entry?.setSensitive(false, false)
        entry?.let { iconManager.updateIcons(it) }
        testScope.runCurrent()
        assertThat(entry?.icons?.shelfIcon?.sourceIcon).isEqualTo(shortcutIc)
    }

    private fun notificationEntry(
        hasShortcut: Boolean,
        hasMessageSenderIcon: Boolean,
        useMessagingStyle: Boolean = true,
        hasLargeIcon: Boolean
    ): NotificationEntry? {
        val n =
            Notification.Builder(mContext, "id")
                .setSmallIcon(smallIc)
                .setContentTitle("Title")
                .setContentText("Text")

        val messagingStyle =
            Notification.MessagingStyle("")
                .addMessage(
                    Notification.MessagingStyle.Message(
                        "",
                        SystemClock.currentThreadTimeMillis(),
                        Person.Builder()
                            .setIcon(if (hasMessageSenderIcon) messageIc else null)
                            .build()
                    )
                )
        if (useMessagingStyle) {
            n.style = messagingStyle
        } else {
            val bundle = Bundle()
            messagingStyle.addExtras(bundle, false, 0) // Set extras but not EXTRA_TEMPLATE
            n.addExtras(bundle)
        }

        if (hasLargeIcon) {
            n.setLargeIcon(largeIc)
        }

        val builder =
            NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setUid(TEST_UID)
                .setId(id++)
                .setNotification(n.build())
                .setChannel(NotificationChannel("id", "", IMPORTANCE_DEFAULT))
                .setUser(UserHandle(ActivityManager.getCurrentUser()))

        if (hasShortcut) {
            builder.setShortcutInfo(shortcut)
        }

        val entry = builder.build()
        entry.row = row
        entry.setSensitive(false, true)
        return entry
    }
}
