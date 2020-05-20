/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.os.SystemClock
import android.service.notification.NotificationListenerService.RankingMap
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.notification.NotificationEntryManagerLogger
import com.android.systemui.statusbar.notification.NotificationFilter
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_FULL_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_IMPORTANT_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_PERSON
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.BUCKET_FOREGROUND_SERVICE
import com.android.systemui.statusbar.notification.stack.BUCKET_SILENT
import com.android.systemui.statusbar.phone.NotificationGroupManager
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationRankingManagerTest : SysuiTestCase() {

    private val lazyMedia: Lazy<NotificationMediaManager> = Lazy {
        mock(NotificationMediaManager::class.java)
    }
    private lateinit var personNotificationIdentifier: PeopleNotificationIdentifier
    private lateinit var rankingManager: TestableNotificationRankingManager
    private lateinit var sectionsManager: NotificationSectionsFeatureManager
    private lateinit var notificationFilter: NotificationFilter

    @Before
    fun setup() {
        personNotificationIdentifier =
                mock(PeopleNotificationIdentifier::class.java)
        sectionsManager = mock(NotificationSectionsFeatureManager::class.java)
        notificationFilter = mock(NotificationFilter::class.java)
        rankingManager = TestableNotificationRankingManager(
                lazyMedia,
                mock(NotificationGroupManager::class.java),
                mock(HeadsUpManager::class.java),
                notificationFilter,
                mock(NotificationEntryManagerLogger::class.java),
                sectionsManager,
                personNotificationIdentifier,
                HighPriorityProvider(personNotificationIdentifier)
        )
    }

    @Test
    fun testSort_highPriorityTrumpsNMSRank() {
        // NMS rank says A and then B. But A is not high priority and B is, so B should sort in
        // front
        val a = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_LOW) // low priority
                .setRank(1) // NMS says rank first
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(
                        Notification.Builder(mContext, "test")
                                .build())
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()

        val b = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH) // high priority
                .setRank(2) // NMS says rank second
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(
                        Notification.Builder(mContext, "test")
                                .build())
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()

        assertEquals(
                listOf(b, a),
                rankingManager.updateRanking(null, listOf(a, b), "test"))
    }

    @Test
    fun testSort_samePriorityUsesNMSRank() {
        // NMS rank says A and then B, and they are the same priority so use that rank
        val aN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val a = NotificationEntryBuilder()
                .setRank(1)
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(aN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()

        val bN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val b = NotificationEntryBuilder()
                .setRank(2)
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(bN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()

        assertEquals(
                listOf(a, b),
                rankingManager.updateRanking(null, listOf(a, b), "test"))
    }

    @Test
    fun testSort_headsUp_trumpsPeople() {
        whenever(sectionsManager.isFilteringEnabled()).thenReturn(true)
        val aN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val a = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(aN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()

        whenever(personNotificationIdentifier.getPeopleNotificationType(a.sbn, a.ranking))
                .thenReturn(TYPE_IMPORTANT_PERSON)

        val bN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val b = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(bN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()
        b.row = mock(ExpandableNotificationRow::class.java).also {
            whenever(it.isHeadsUp).thenReturn(true)
        }

        whenever(personNotificationIdentifier.getPeopleNotificationType(a.sbn, a.ranking))
                .thenReturn(TYPE_PERSON)

        assertEquals(listOf(b, a), rankingManager.updateRanking(null, listOf(a, b), "test"))
    }

    @Test
    fun testSort_importantPeople() {
        whenever(sectionsManager.isFilteringEnabled()).thenReturn(true)
        val aN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val a = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(aN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.user)
                .setOverrideGroupKey("")
                .build()
        whenever(personNotificationIdentifier.getPeopleNotificationType(a.sbn, a.ranking))
                .thenReturn(TYPE_PERSON)

        val bN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val b = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(bN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.user)
                .setOverrideGroupKey("")
                .build()
        whenever(personNotificationIdentifier.getPeopleNotificationType(b.sbn, b.ranking))
                .thenReturn(TYPE_IMPORTANT_PERSON)

        whenever(personNotificationIdentifier.compareTo(TYPE_PERSON, TYPE_IMPORTANT_PERSON))
                .thenReturn(TYPE_IMPORTANT_PERSON.compareTo(TYPE_PERSON))
        whenever(personNotificationIdentifier.compareTo(TYPE_IMPORTANT_PERSON, TYPE_PERSON))
                .thenReturn(TYPE_PERSON.compareTo(TYPE_IMPORTANT_PERSON))

        assertEquals(
                listOf(b, a),
                rankingManager.updateRanking(null, listOf(a, b), "test"))
    }

    @Test
    fun testSort_fullPeople() {
        whenever(sectionsManager.isFilteringEnabled()).thenReturn(true)
        val aN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val a = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(aN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.user)
                .setOverrideGroupKey("")
                .build()
        whenever(personNotificationIdentifier.getPeopleNotificationType(a.sbn, a.ranking))
                .thenReturn(TYPE_PERSON)

        val bN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val b = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(bN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.user)
                .setOverrideGroupKey("")
                .build()
        whenever(personNotificationIdentifier.getPeopleNotificationType(b.sbn, b.ranking))
                .thenReturn(TYPE_FULL_PERSON)

        whenever(personNotificationIdentifier.compareTo(TYPE_PERSON, TYPE_FULL_PERSON))
                .thenReturn(TYPE_FULL_PERSON.compareTo(TYPE_PERSON))
        whenever(personNotificationIdentifier.compareTo(TYPE_FULL_PERSON, TYPE_PERSON))
                .thenReturn(TYPE_PERSON.compareTo(TYPE_FULL_PERSON))

        assertEquals(
                listOf(b, a),
                rankingManager.updateRanking(null, listOf(a, b), "test"))
    }

    @Test
    fun testSort_properlySetsAlertingBucket() {
        val notif = Notification.Builder(mContext, "test") .build()

        val e = NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(notif)
                .setUser(mContext.user)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setOverrideGroupKey("")
                .build()

        modifyRanking(e).setImportance(IMPORTANCE_DEFAULT).build()

        rankingManager.updateRanking(RankingMap(arrayOf(e.ranking)), listOf(e), "test")
        assertEquals(e.bucket, BUCKET_ALERTING)
    }

    @Test
    fun testSort_properlySetsSilentBucket() {
        val notif = Notification.Builder(mContext, "test") .build()

        val e = NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(notif)
                .setUser(mContext.user)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setOverrideGroupKey("")
                .build()

        modifyRanking(e).setImportance(IMPORTANCE_LOW).build()

        rankingManager.updateRanking(RankingMap(arrayOf(e.ranking)), listOf(e), "test")
        assertEquals(e.bucket, BUCKET_SILENT)
    }

    @Test
    fun testFilter_resetsInitalizationTime() {
        // GIVEN an entry that was initialized 1 second ago
        val notif = Notification.Builder(mContext, "test") .build()

        val e = NotificationEntryBuilder()
            .setPkg("pkg")
            .setOpPkg("pkg")
            .setTag("tag")
            .setNotification(notif)
            .setUser(mContext.user)
            .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
            .setOverrideGroupKey("")
            .build()

        e.setInitializationTime(SystemClock.elapsedRealtime() - 1000)
        assertEquals(true, e.hasFinishedInitialization())

        // WHEN we update ranking and filter out the notification entry
        whenever(notificationFilter.shouldFilterOut(e)).thenReturn(true)
        rankingManager.updateRanking(RankingMap(arrayOf(e.ranking)), listOf(e), "test")

        // THEN the initialization time for the entry is reset
        assertEquals(false, e.hasFinishedInitialization())
    }

    @Test
    fun testSort_colorizedForegroundService() {
        whenever(sectionsManager.isFilteringEnabled()).thenReturn(true)

        val a = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(
                        Notification.Builder(mContext, "test")
                                .build())
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()

        val b = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_DEFAULT) // high priority
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(mock(Notification::class.java).also { notif ->
                    whenever(notif.isForegroundService).thenReturn(true)
                    whenever(notif.isColorized).thenReturn(true)
                })
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()

        val cN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val c = NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(cN)
                .setChannel(NotificationChannel("test", "", IMPORTANCE_DEFAULT))
                .setUser(mContext.user)
                .setOverrideGroupKey("")
                .build()
        whenever(personNotificationIdentifier.getPeopleNotificationType(a.sbn, a.ranking))
                .thenReturn(TYPE_IMPORTANT_PERSON)

        assertThat(rankingManager.updateRanking(null, listOf(a, b, c), "test"))
                .containsExactly(b, c, a)
        assertThat(b.bucket).isEqualTo(BUCKET_FOREGROUND_SERVICE)
    }

    internal class TestableNotificationRankingManager(
        mediaManager: Lazy<NotificationMediaManager>,
        groupManager: NotificationGroupManager,
        headsUpManager: HeadsUpManager,
        filter: NotificationFilter,
        logger: NotificationEntryManagerLogger,
        sectionsFeatureManager: NotificationSectionsFeatureManager,
        peopleNotificationIdentifier: PeopleNotificationIdentifier,
        highPriorityProvider: HighPriorityProvider
    ) : NotificationRankingManager(
        mediaManager,
        groupManager,
        headsUpManager,
        filter,
        logger,
        sectionsFeatureManager,
        peopleNotificationIdentifier,
        highPriorityProvider
    ) {
        fun applyTestRankingMap(r: RankingMap) {
            rankingMap = r
        }
    }
}
