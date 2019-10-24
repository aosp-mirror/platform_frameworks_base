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
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.Person
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner

import org.junit.runner.RunWith

import androidx.test.filters.SmallTest

import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.NotificationEntryBuilder
import com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.notification.NotificationFilter
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.logging.NotifLog
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT
import com.android.systemui.statusbar.phone.NotificationGroupManager
import com.android.systemui.statusbar.policy.HeadsUpManager
import dagger.Lazy
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue

import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationRankingManagerTest
    : SysuiTestCase() {

    private var lazyMedia: Lazy<NotificationMediaManager> = Lazy {
        mock<NotificationMediaManager>(NotificationMediaManager::class.java)
    }

    private val rankingManager = TestableNotificationRankingManager(
            lazyMedia,
            mock<NotificationGroupManager>(NotificationGroupManager::class.java),
            mock<HeadsUpManager>(HeadsUpManager::class.java),
            mock<NotificationFilter>(NotificationFilter::class.java),
            mock<NotifLog>(NotifLog::class.java),
            mock<NotificationSectionsFeatureManager>(NotificationSectionsFeatureManager::class.java)
    )

    @Before
    fun setup() {
    }

    @Test
    fun testPeopleNotification_isHighPriority() {
        val person = Person.Builder()
                .setName("name")
                .setKey("abc")
                .setUri("uri")
                .setBot(true)
                .build()

        val notification = Notification.Builder(mContext, "test")
                .addPerson(person)
                .build()

        val sbn = StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.user, "", 0)

        val e = NotificationEntryBuilder()
                .setNotification(notification)
                .setSbn(sbn)
                .build()

        assertTrue(rankingManager.isHighPriority2(e))
    }

    @Test
    fun messagingStyleHighPriority() {

        val notif = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()

        val sbn = StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notif, mContext.getUser(), "", 0)

        val e = NotificationEntryBuilder()
                .setNotification(notif)
                .setSbn(sbn)
                .build()

        assertTrue(rankingManager.isHighPriority2(e))
    }

    @Test
    fun lowForegroundHighPriority() {
        val notification = mock(Notification::class.java)
        `when`<Boolean>(notification.isForegroundService).thenReturn(true)

        val sbn = StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.user, "", 0)

        val e = NotificationEntryBuilder()
                .setNotification(notification)
                .setSbn(sbn)
                .build()

        modifyRanking(e)
                .setImportance(IMPORTANCE_LOW)
                .build()

        assertTrue(rankingManager.isHighPriority2(e))
    }

    @Test
    fun userChangeTrumpsHighPriorityCharacteristics() {
        val person = Person.Builder()
                .setName("name")
                .setKey("abc")
                .setUri("uri")
                .setBot(true)
                .build()

        val notification = Notification.Builder(mContext, "test")
                .addPerson(person)
                .setStyle(Notification.MessagingStyle(""))
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build()

        val sbn = StatusBarNotification("pkg", "pkg", 0, "tag", 0, 0,
                notification, mContext.user, "", 0)

        val channel = NotificationChannel("a", "a", IMPORTANCE_LOW)
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE)

        val e = NotificationEntryBuilder()
                .setSbn(sbn)
                .setChannel(channel)
                .build()

        assertFalse(rankingManager.isHighPriority2(e))
    }

    @Test
    fun testSort_highPriorityTrumpsNMSRank() {
        // NMS rank says A and then B. But A is not high priority and B is, so B should sort in
        // front
        val aN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val a = NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(aN)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()

        a.setIsHighPriority(false)

        modifyRanking(a)
                .setImportance(IMPORTANCE_LOW)
                .setRank(1)
                .build()

        val bN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val b = NotificationEntryBuilder()
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(bN)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()
        b.setIsHighPriority(true)

        modifyRanking(b)
                .setImportance(IMPORTANCE_LOW)
                .setRank(2)
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
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(aN)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()
        a.setIsHighPriority(false)

        modifyRanking(a)
                .setImportance(IMPORTANCE_LOW)
                .setRank(1)
                .build()

        val bN = Notification.Builder(mContext, "test")
                .setStyle(Notification.MessagingStyle(""))
                .build()
        val b = NotificationEntryBuilder()
                .setPkg("pkg2")
                .setOpPkg("pkg2")
                .setTag("tag")
                .setNotification(bN)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build()
        b.setIsHighPriority(false)

        modifyRanking(b)
                .setImportance(IMPORTANCE_LOW)
                .setRank(2)
                .build()

        assertEquals(
                listOf(a, b),
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
                .setOverrideGroupKey("")
                .build()

        modifyRanking(e).setImportance(IMPORTANCE_DEFAULT) .build()

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
                .setOverrideGroupKey("")
                .build()

        modifyRanking(e).setImportance(IMPORTANCE_LOW).build()

        rankingManager.updateRanking(RankingMap(arrayOf(e.ranking)), listOf(e), "test")
        assertEquals(e.bucket, BUCKET_SILENT)
    }

    internal class TestableNotificationRankingManager(
        mediaManager: Lazy<NotificationMediaManager>,
        groupManager: NotificationGroupManager,
        headsUpManager: HeadsUpManager,
        filter: NotificationFilter,
        notifLog: NotifLog,
        sectionsFeatureManager: NotificationSectionsFeatureManager
    ) : NotificationRankingManager(
        mediaManager,
        groupManager,
        headsUpManager,
        filter,
        notifLog,
        sectionsFeatureManager
    ) {

        fun isHighPriority2(e: NotificationEntry): Boolean {
            return isHighPriority(e)
        }

        fun applyTestRankingMap(r: RankingMap) {
            rankingMap = r
        }
    }
}