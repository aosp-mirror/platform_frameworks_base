/*
 *
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.logging

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.stats.sysui.NotificationEnums
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationMemoryMeterTest : SysuiTestCase() {

    @Test
    fun currentNotificationMemoryUse_plainNotification() {
        val notification = createBasicNotification().build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = notification.getLargeIcon().bitmap.allocationByteCount,
            extras = 3316,
            bigPicture = 0,
            extender = 0,
            style = NotificationEnums.STYLE_NONE,
            styleIcon = 0,
            hasCustomView = false,
        )
    }

    @Test
    fun currentNotificationMemoryUse_rankerGroupNotification() {
        val notification = createBasicNotification().build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(
                createNotificationEntry(createBasicNotification().setGroup("ranker_group").build())
            )
        assertNotificationObjectSizes(
            memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = notification.getLargeIcon().bitmap.allocationByteCount,
            extras = 3316,
            bigPicture = 0,
            extender = 0,
            style = NotificationEnums.STYLE_RANKER_GROUP,
            styleIcon = 0,
            hasCustomView = false,
        )
    }

    @Test
    fun currentNotificationMemoryUse_plainNotification_dontDoubleCountSameBitmap() {
        val icon = Icon.createWithBitmap(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
        val notification = createBasicNotification().setLargeIcon(icon).setSmallIcon(icon).build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse = memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = 0,
            extras = 3316,
            bigPicture = 0,
            extender = 0,
            style = NotificationEnums.STYLE_NONE,
            styleIcon = 0,
            hasCustomView = false,
        )
    }

    @Test
    fun currentNotificationMemoryUse_customViewNotification_marksTrue() {
        val notification =
            createBasicNotification()
                .setCustomContentView(
                    RemoteViews(context.packageName, android.R.layout.list_content)
                )
                .build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse = memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = notification.getLargeIcon().bitmap.allocationByteCount,
            extras = 3384,
            bigPicture = 0,
            extender = 0,
            style = NotificationEnums.STYLE_NONE,
            styleIcon = 0,
            hasCustomView = true,
        )
    }

    @Test
    fun currentNotificationMemoryUse_notificationWithDataIcon_calculatesCorrectly() {
        val dataIcon = Icon.createWithData(ByteArray(444444), 0, 444444)
        val notification =
            createBasicNotification().setLargeIcon(dataIcon).setSmallIcon(dataIcon).build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse = memoryUse,
            smallIcon = 444444,
            largeIcon = 0,
            extras = 3212,
            bigPicture = 0,
            extender = 0,
            style = NotificationEnums.STYLE_NONE,
            styleIcon = 0,
            hasCustomView = false,
        )
    }

    @Test
    fun currentNotificationMemoryUse_bigPictureStyle() {
        val bigPicture =
            Icon.createWithBitmap(Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888))
        val bigPictureIcon =
            Icon.createWithAdaptiveBitmap(Bitmap.createBitmap(386, 432, Bitmap.Config.ARGB_8888))
        val notification =
            createBasicNotification()
                .setStyle(
                    Notification.BigPictureStyle()
                        .bigPicture(bigPicture)
                        .bigLargeIcon(bigPictureIcon)
                )
                .build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse = memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = notification.getLargeIcon().bitmap.allocationByteCount,
            extras = 4092,
            bigPicture = bigPicture.bitmap.allocationByteCount,
            extender = 0,
            style = NotificationEnums.STYLE_BIG_PICTURE,
            styleIcon = bigPictureIcon.bitmap.allocationByteCount,
            hasCustomView = false,
        )
    }

    @Test
    fun currentNotificationMemoryUse_callingStyle() {
        val personIcon =
            Icon.createWithBitmap(Bitmap.createBitmap(386, 432, Bitmap.Config.ARGB_8888))
        val person = Person.Builder().setIcon(personIcon).setName("Person").build()
        val fakeIntent =
            PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val notification =
            createBasicNotification()
                .setStyle(Notification.CallStyle.forIncomingCall(person, fakeIntent, fakeIntent))
                .build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse = memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = notification.getLargeIcon().bitmap.allocationByteCount,
            extras = 4084,
            bigPicture = 0,
            extender = 0,
            style = NotificationEnums.STYLE_CALL,
            styleIcon = personIcon.bitmap.allocationByteCount,
            hasCustomView = false,
        )
    }

    @Test
    fun currentNotificationMemoryUse_messagingStyle() {
        val personIcon =
            Icon.createWithBitmap(Bitmap.createBitmap(386, 432, Bitmap.Config.ARGB_8888))
        val person = Person.Builder().setIcon(personIcon).setName("Person").build()
        val message = Notification.MessagingStyle.Message("Message!", 4323, person)
        val historicPersonIcon =
            Icon.createWithBitmap(Bitmap.createBitmap(348, 382, Bitmap.Config.ARGB_8888))
        val historicPerson =
            Person.Builder().setIcon(historicPersonIcon).setName("Historic person").build()
        val historicMessage =
            Notification.MessagingStyle.Message("Historic message!", 5848, historicPerson)

        val notification =
            createBasicNotification()
                .setStyle(
                    Notification.MessagingStyle(person)
                        .addMessage(message)
                        .addHistoricMessage(historicMessage)
                )
                .build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse = memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = notification.getLargeIcon().bitmap.allocationByteCount,
            extras = 5024,
            bigPicture = 0,
            extender = 0,
            style = NotificationEnums.STYLE_MESSAGING,
            styleIcon =
                personIcon.bitmap.allocationByteCount +
                    historicPersonIcon.bitmap.allocationByteCount,
            hasCustomView = false,
        )
    }

    @Test
    fun currentNotificationMemoryUse_carExtender() {
        val carIcon = Bitmap.createBitmap(432, 322, Bitmap.Config.ARGB_8888)
        val extender = Notification.CarExtender().setLargeIcon(carIcon)
        val notification = createBasicNotification().extend(extender).build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse = memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = notification.getLargeIcon().bitmap.allocationByteCount,
            extras = 3612,
            bigPicture = 0,
            extender = 556656,
            style = NotificationEnums.STYLE_NONE,
            styleIcon = 0,
            hasCustomView = false,
        )
    }

    @Test
    fun currentNotificationMemoryUse_tvWearExtender() {
        val tvExtender = Notification.TvExtender().setChannel("channel2")
        val wearBackground = Bitmap.createBitmap(443, 433, Bitmap.Config.ARGB_8888)
        val wearExtender = Notification.WearableExtender().setBackground(wearBackground)
        val notification = createBasicNotification().extend(tvExtender).extend(wearExtender).build()
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(createNotificationEntry(notification))
        assertNotificationObjectSizes(
            memoryUse = memoryUse,
            smallIcon = notification.smallIcon.bitmap.allocationByteCount,
            largeIcon = notification.getLargeIcon().bitmap.allocationByteCount,
            extras = 3820,
            bigPicture = 0,
            extender = 388 + wearBackground.allocationByteCount,
            style = NotificationEnums.STYLE_NONE,
            styleIcon = 0,
            hasCustomView = false,
        )
    }

    private fun createBasicNotification(): Notification.Builder {
        val smallIcon =
            Icon.createWithBitmap(Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888))
        val largeIcon =
            Icon.createWithBitmap(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888))
        return Notification.Builder(context)
            .setSmallIcon(smallIcon)
            .setLargeIcon(largeIcon)
            .setContentTitle("This is a title")
            .setContentText("This is content text.")
    }

    /** This will generate a nicer error message than comparing objects */
    private fun assertNotificationObjectSizes(
        memoryUse: NotificationMemoryUsage,
        smallIcon: Int,
        largeIcon: Int,
        extras: Int,
        bigPicture: Int,
        extender: Int,
        style: Int,
        styleIcon: Int,
        hasCustomView: Boolean,
    ) {
        assertThat(memoryUse.packageName).isEqualTo("test_pkg")
        assertThat(memoryUse.notificationKey)
            .isEqualTo(NotificationUtils.logKey("0|test_pkg|0|test|0"))
        assertThat(memoryUse.objectUsage.smallIcon).isEqualTo(smallIcon)
        assertThat(memoryUse.objectUsage.largeIcon).isEqualTo(largeIcon)
        assertThat(memoryUse.objectUsage.bigPicture).isEqualTo(bigPicture)
        assertThat(memoryUse.objectUsage.style).isEqualTo(style)
        assertThat(memoryUse.objectUsage.styleIcon).isEqualTo(styleIcon)
        assertThat(memoryUse.objectUsage.hasCustomView).isEqualTo(hasCustomView)
    }

    private fun getUseObject(
        singleItemUseList: List<NotificationMemoryUsage>,
    ): NotificationMemoryUsage {
        assertThat(singleItemUseList).hasSize(1)
        return singleItemUseList[0]
    }

    private fun createNotificationEntry(
        notification: Notification,
    ): NotificationEntry =
        NotificationEntryBuilder().setTag("test").setNotification(notification).build()
}
