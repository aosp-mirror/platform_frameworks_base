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

package com.android.systemui.statusbar.notification.promoted

import android.app.Notification
import android.app.Notification.BigPictureStyle
import android.app.Notification.BigTextStyle
import android.app.Notification.CallStyle
import android.app.Notification.MessagingStyle
import android.app.Notification.ProgressStyle
import android.app.Notification.ProgressStyle.Segment
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel.Style
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PromotedNotificationContentExtractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val provider =
        FakePromotedNotificationsProvider().also { kosmos.promotedNotificationsProvider = it }

    private val underTest = kosmos.promotedNotificationContentExtractor

    @Test
    @DisableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun shouldNotExtract_bothFlagsDisabled() {
        val notif = createEntry().also { provider.promotedEntries.add(it) }
        val content = extractContent(notif)
        assertThat(content).isNull()
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    @DisableFlags(StatusBarNotifChips.FLAG_NAME)
    fun shouldExtract_promotedNotificationUiFlagEnabled() {
        val entry = createEntry().also { provider.promotedEntries.add(it) }
        val content = extractContent(entry)
        assertThat(content).isNotNull()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    @DisableFlags(PromotedNotificationUi.FLAG_NAME)
    fun shouldExtract_statusBarNotifChipsFlagEnabled() {
        val entry = createEntry().also { provider.promotedEntries.add(it) }
        val content = extractContent(entry)
        assertThat(content).isNotNull()
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun shouldExtract_bothFlagsEnabled() {
        val entry = createEntry().also { provider.promotedEntries.add(it) }
        val content = extractContent(entry)
        assertThat(content).isNotNull()
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun shouldNotExtract_providerDidNotPromote() {
        val entry = createEntry().also { provider.promotedEntries.remove(it) }
        val content = extractContent(entry)
        assertThat(content).isNull()
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun extractContent_commonFields() {
        val entry =
            createEntry {
                    setSubText(TEST_SUB_TEXT)
                    setContentTitle(TEST_CONTENT_TITLE)
                    setContentText(TEST_CONTENT_TEXT)
                }
                .also { provider.promotedEntries.add(it) }

        val content = extractContent(entry)

        assertThat(content).isNotNull()
        assertThat(content?.subText).isEqualTo(TEST_SUB_TEXT)
        assertThat(content?.title).isEqualTo(TEST_CONTENT_TITLE)
        assertThat(content?.text).isEqualTo(TEST_CONTENT_TEXT)
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun extractContent_fromBigPictureStyle() {
        val entry =
            createEntry { setStyle(BigPictureStyle()) }.also { provider.promotedEntries.add(it) }

        val content = extractContent(entry)

        assertThat(content).isNotNull()
        assertThat(content?.style).isEqualTo(Style.BigPicture)
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun extractContent_fromBigTextStyle() {
        val entry =
            createEntry { setStyle(BigTextStyle()) }.also { provider.promotedEntries.add(it) }

        val content = extractContent(entry)

        assertThat(content).isNotNull()
        assertThat(content?.style).isEqualTo(Style.BigText)
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun extractContent_fromCallStyle() {
        val hangUpIntent =
            PendingIntent.getBroadcast(context, 0, Intent("hangup"), PendingIntent.FLAG_IMMUTABLE)

        val entry =
            createEntry { setStyle(CallStyle.forOngoingCall(TEST_PERSON, hangUpIntent)) }
                .also { provider.promotedEntries.add(it) }

        val content = extractContent(entry)

        assertThat(content).isNotNull()
        assertThat(content?.style).isEqualTo(Style.Call)
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun extractContent_fromProgressStyle() {
        val entry =
            createEntry {
                    setStyle(ProgressStyle().addProgressSegment(Segment(100)).setProgress(75))
                }
                .also { provider.promotedEntries.add(it) }

        val content = extractContent(entry)

        assertThat(content).isNotNull()
        assertThat(content?.style).isEqualTo(Style.Progress)
        assertThat(content?.progress).isNotNull()
        assertThat(content?.progress?.progress).isEqualTo(75)
        assertThat(content?.progress?.progressMax).isEqualTo(100)
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun extractContent_fromIneligibleStyle() {
        val entry =
            createEntry {
                    setStyle(
                        MessagingStyle(TEST_PERSON).addMessage("message text", 0L, TEST_PERSON)
                    )
                }
                .also { provider.promotedEntries.add(it) }

        val content = extractContent(entry)

        assertThat(content).isNotNull()
        assertThat(content?.style).isEqualTo(Style.Ineligible)
    }

    private fun extractContent(entry: NotificationEntry): PromotedNotificationContentModel? {
        val recoveredBuilder = Notification.Builder(context, entry.sbn.notification)
        return underTest.extractContent(entry, recoveredBuilder)
    }

    private fun createEntry(builderBlock: Notification.Builder.() -> Unit = {}): NotificationEntry {
        val notif = Notification.Builder(context, "a").also(builderBlock).build()
        return NotificationEntryBuilder().setNotification(notif).build()
    }

    companion object {
        private const val TEST_SUB_TEXT = "sub text"
        private const val TEST_CONTENT_TITLE = "content title"
        private const val TEST_CONTENT_TEXT = "content text"

        private const val TEST_PERSON_NAME = "person name"
        private const val TEST_PERSON_KEY = "person key"
        private val TEST_PERSON =
            Person.Builder().setKey(TEST_PERSON_KEY).setName(TEST_PERSON_NAME).build()
    }
}
