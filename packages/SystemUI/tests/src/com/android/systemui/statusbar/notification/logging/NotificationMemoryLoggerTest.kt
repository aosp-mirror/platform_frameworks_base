/*
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
import android.app.StatsManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.stats.sysui.NotificationEnums
import android.util.StatsEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.assertLogsWtf
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import java.lang.RuntimeException
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationMemoryLoggerTest : SysuiTestCase() {

    @Rule @JvmField val expect = Expect.create()

    private val bgExecutor = FakeExecutor(FakeSystemClock())
    private val immediate = Dispatchers.Main.immediate

    @Mock private lateinit var statsManager: StatsManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun onInit_registersCallback() {
        val logger = createLoggerWithNotifications(listOf())
        logger.init()
        verify(statsManager)
            .setPullAtomCallback(SysUiStatsLog.NOTIFICATION_MEMORY_USE, null, bgExecutor, logger)
    }

    @Test
    fun onPullAtom_wrongAtomId_returnsSkip() {
        val logger = createLoggerWithNotifications(listOf())
        val data: MutableList<StatsEvent> = mutableListOf()
        assertThat(logger.onPullAtom(111, data)).isEqualTo(StatsManager.PULL_SKIP)
        assertThat(data).isEmpty()
    }

    @Test
    fun onPullAtom_emptyNotifications_returnsZeros() {
        val logger = createLoggerWithNotifications(listOf())
        val data: MutableList<StatsEvent> = mutableListOf()
        assertThat(logger.onPullAtom(SysUiStatsLog.NOTIFICATION_MEMORY_USE, data))
            .isEqualTo(StatsManager.PULL_SUCCESS)
        assertThat(data).isEmpty()
    }

    @Test
    fun onPullAtom_notificationPassed_populatesData() {
        val icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888))
        val notification =
            Notification.Builder(context).setSmallIcon(icon).setContentTitle("title").build()
        val logger = createLoggerWithNotifications(listOf(notification))
        val data: MutableList<StatsEvent> = mutableListOf()

        assertThat(logger.onPullAtom(SysUiStatsLog.NOTIFICATION_MEMORY_USE, data))
            .isEqualTo(StatsManager.PULL_SUCCESS)
        assertThat(data).hasSize(1)
    }

    @Test
    fun onPullAtom_multipleNotificationsPassed_populatesData() {
        val icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888))
        val notification =
            Notification.Builder(context).setSmallIcon(icon).setContentTitle("title").build()
        val iconTwo = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888))

        val notificationTwo =
            Notification.Builder(context)
                .setStyle(Notification.BigTextStyle().bigText("text"))
                .setSmallIcon(iconTwo)
                .setContentTitle("titleTwo")
                .build()
        val logger = createLoggerWithNotifications(listOf(notification, notificationTwo))
        val data: MutableList<StatsEvent> = mutableListOf()

        assertThat(logger.onPullAtom(SysUiStatsLog.NOTIFICATION_MEMORY_USE, data))
            .isEqualTo(StatsManager.PULL_SUCCESS)
        assertThat(data).hasSize(2)
    }

    @Test
    fun onPullAtom_throwsInterruptedException_failsGracefully() {
        val pipeline: NotifPipeline = mock()
        whenever(pipeline.allNotifs).thenAnswer { throw InterruptedException("Timeout") }
        val logger = NotificationMemoryLogger(pipeline, statsManager, immediate, bgExecutor)
        assertThat(logger.onPullAtom(SysUiStatsLog.NOTIFICATION_MEMORY_USE, mutableListOf()))
            .isEqualTo(StatsManager.PULL_SKIP)
    }

    @Test
    fun onPullAtom_throwsRuntimeException_failsGracefully() {
        val pipeline: NotifPipeline = mock()
        whenever(pipeline.allNotifs).thenThrow(RuntimeException("Something broke!"))
        val logger = NotificationMemoryLogger(pipeline, statsManager, immediate, bgExecutor)
        assertLogsWtf {
            assertThat(logger.onPullAtom(SysUiStatsLog.NOTIFICATION_MEMORY_USE, mutableListOf()))
                .isEqualTo(StatsManager.PULL_SKIP)
        }
    }

    @Test
    fun aggregateMemoryUsageData_returnsCorrectlyAggregatedSamePackageData() {
        val usage = getPresetMemoryUsages()
        val aggregateUsage = aggregateMemoryUsageData(usage)

        assertThat(aggregateUsage).hasSize(3)
        assertThat(aggregateUsage)
            .containsKey(Pair("package 1", NotificationEnums.STYLE_BIG_PICTURE))

        // Aggregated fields
        val aggregatedData =
            aggregateUsage[Pair("package 1", NotificationEnums.STYLE_BIG_PICTURE)]!!
        val presetUsage1 = usage[0]
        val presetUsage2 = usage[1]
        assertAggregatedData(
            aggregatedData,
            2,
            2,
            smallIconObject =
                presetUsage1.objectUsage.smallIcon + presetUsage2.objectUsage.smallIcon,
            smallIconBitmapCount = 2,
            largeIconObject =
                presetUsage1.objectUsage.largeIcon + presetUsage2.objectUsage.largeIcon,
            largeIconBitmapCount = 2,
            bigPictureObject =
                presetUsage1.objectUsage.bigPicture + presetUsage2.objectUsage.bigPicture,
            bigPictureBitmapCount = 2,
            extras = presetUsage1.objectUsage.extras + presetUsage2.objectUsage.extras,
            extenders = presetUsage1.objectUsage.extender + presetUsage2.objectUsage.extender,
            // Only totals need to be summarized.
            smallIconViews =
                presetUsage1.viewUsage[0].smallIcon + presetUsage2.viewUsage[0].smallIcon,
            largeIconViews =
                presetUsage1.viewUsage[0].largeIcon + presetUsage2.viewUsage[0].largeIcon,
            systemIconViews =
                presetUsage1.viewUsage[0].systemIcons + presetUsage2.viewUsage[0].systemIcons,
            styleViews = presetUsage1.viewUsage[0].style + presetUsage2.viewUsage[0].style,
            customViews =
                presetUsage1.viewUsage[0].customViews + presetUsage2.viewUsage[0].customViews,
            softwareBitmaps =
                presetUsage1.viewUsage[0].softwareBitmapsPenalty +
                    presetUsage2.viewUsage[0].softwareBitmapsPenalty,
            seenCount = 0
        )
    }

    @Test
    fun aggregateMemoryUsageData_correctlySeparatesDifferentStyles() {
        val usage = getPresetMemoryUsages()
        val aggregateUsage = aggregateMemoryUsageData(usage)

        assertThat(aggregateUsage).hasSize(3)
        assertThat(aggregateUsage)
            .containsKey(Pair("package 1", NotificationEnums.STYLE_BIG_PICTURE))
        assertThat(aggregateUsage).containsKey(Pair("package 1", NotificationEnums.STYLE_BIG_TEXT))

        // Different style should be separate
        val separateStyleData =
            aggregateUsage[Pair("package 1", NotificationEnums.STYLE_BIG_TEXT)]!!
        val presetUsage = usage[2]
        assertAggregatedData(
            separateStyleData,
            1,
            1,
            presetUsage.objectUsage.smallIcon,
            1,
            presetUsage.objectUsage.largeIcon,
            1,
            presetUsage.objectUsage.bigPicture,
            1,
            presetUsage.objectUsage.extras,
            presetUsage.objectUsage.extender,
            presetUsage.viewUsage[0].smallIcon,
            presetUsage.viewUsage[0].largeIcon,
            presetUsage.viewUsage[0].systemIcons,
            presetUsage.viewUsage[0].style,
            presetUsage.viewUsage[0].customViews,
            presetUsage.viewUsage[0].softwareBitmapsPenalty,
            0
        )
    }

    @Test
    fun aggregateMemoryUsageData_correctlySeparatesDifferentProcess() {
        val usage = getPresetMemoryUsages()
        val aggregateUsage = aggregateMemoryUsageData(usage)

        assertThat(aggregateUsage).hasSize(3)
        assertThat(aggregateUsage)
            .containsKey(Pair("package 2", NotificationEnums.STYLE_BIG_PICTURE))

        // Different UID/package should also be separate
        val separatePackageData =
            aggregateUsage[Pair("package 2", NotificationEnums.STYLE_BIG_PICTURE)]!!
        val presetUsage = usage[3]
        assertAggregatedData(
            separatePackageData,
            1,
            1,
            presetUsage.objectUsage.smallIcon,
            1,
            presetUsage.objectUsage.largeIcon,
            1,
            presetUsage.objectUsage.bigPicture,
            1,
            presetUsage.objectUsage.extras,
            presetUsage.objectUsage.extender,
            presetUsage.viewUsage[0].smallIcon,
            presetUsage.viewUsage[0].largeIcon,
            presetUsage.viewUsage[0].systemIcons,
            presetUsage.viewUsage[0].style,
            presetUsage.viewUsage[0].customViews,
            presetUsage.viewUsage[0].softwareBitmapsPenalty,
            0
        )
    }

    private fun createLoggerWithNotifications(
        notifications: List<Notification>
    ): NotificationMemoryLogger {
        val pipeline: NotifPipeline = mock()
        val notifications =
            notifications.map { notification ->
                NotificationEntryBuilder().setTag("test").setNotification(notification).build()
            }
        whenever(pipeline.allNotifs).thenReturn(notifications)
        return NotificationMemoryLogger(pipeline, statsManager, immediate, bgExecutor)
    }

    /**
     * Short hand for making sure the passed NotificationMemoryUseAtomBuilder object contains
     * expected values.
     */
    private fun assertAggregatedData(
        value: NotificationMemoryLogger.NotificationMemoryUseAtomBuilder,
        count: Int,
        countWithInflatedViews: Int,
        smallIconObject: Int,
        smallIconBitmapCount: Int,
        largeIconObject: Int,
        largeIconBitmapCount: Int,
        bigPictureObject: Int,
        bigPictureBitmapCount: Int,
        extras: Int,
        extenders: Int,
        smallIconViews: Int,
        largeIconViews: Int,
        systemIconViews: Int,
        styleViews: Int,
        customViews: Int,
        softwareBitmaps: Int,
        seenCount: Int
    ) {
        expect.withMessage("count").that(value.count).isEqualTo(count)
        expect
            .withMessage("countWithInflatedViews")
            .that(value.countWithInflatedViews)
            .isEqualTo(countWithInflatedViews)
        expect.withMessage("smallIconObject").that(value.smallIconObject).isEqualTo(smallIconObject)
        expect
            .withMessage("smallIconBitmapCount")
            .that(value.smallIconBitmapCount)
            .isEqualTo(smallIconBitmapCount)
        expect.withMessage("largeIconObject").that(value.largeIconObject).isEqualTo(largeIconObject)
        expect
            .withMessage("largeIconBitmapCount")
            .that(value.largeIconBitmapCount)
            .isEqualTo(largeIconBitmapCount)
        expect
            .withMessage("bigPictureObject")
            .that(value.bigPictureObject)
            .isEqualTo(bigPictureObject)
        expect
            .withMessage("bigPictureBitmapCount")
            .that(value.bigPictureBitmapCount)
            .isEqualTo(bigPictureBitmapCount)
        expect.withMessage("extras").that(value.extras).isEqualTo(extras)
        expect.withMessage("extenders").that(value.extenders).isEqualTo(extenders)
        expect.withMessage("smallIconViews").that(value.smallIconViews).isEqualTo(smallIconViews)
        expect.withMessage("largeIconViews").that(value.largeIconViews).isEqualTo(largeIconViews)
        expect.withMessage("systemIconViews").that(value.systemIconViews).isEqualTo(systemIconViews)
        expect.withMessage("styleViews").that(value.styleViews).isEqualTo(styleViews)
        expect.withMessage("customViews").that(value.customViews).isEqualTo(customViews)
        expect.withMessage("softwareBitmaps").that(value.softwareBitmaps).isEqualTo(softwareBitmaps)
        expect.withMessage("seenCount").that(value.seenCount).isEqualTo(seenCount)
    }

    /** Generates a static set of [NotificationMemoryUsage] objects. */
    private fun getPresetMemoryUsages() =
        listOf(
            // A pair of notifications that have to be aggregated, same UID and style
            NotificationMemoryUsage(
                "package 1",
                384,
                "key1",
                Notification.Builder(context).setStyle(Notification.BigPictureStyle()).build(),
                NotificationObjectUsage(
                    23,
                    45,
                    67,
                    NotificationEnums.STYLE_BIG_PICTURE,
                    12,
                    483,
                    4382,
                    true
                ),
                listOf(
                    NotificationViewUsage(ViewType.TOTAL, 493, 584, 4833, 584, 4888, 5843),
                    NotificationViewUsage(
                        ViewType.PRIVATE_CONTRACTED_VIEW,
                        100,
                        250,
                        300,
                        594,
                        6000,
                        5843
                    )
                )
            ),
            NotificationMemoryUsage(
                "package 1",
                384,
                "key2",
                Notification.Builder(context).setStyle(Notification.BigPictureStyle()).build(),
                NotificationObjectUsage(
                    77,
                    54,
                    34,
                    NotificationEnums.STYLE_BIG_PICTURE,
                    77,
                    432,
                    2342,
                    true
                ),
                listOf(
                    NotificationViewUsage(ViewType.TOTAL, 3245, 1234, 7653, 543, 765, 7655),
                    NotificationViewUsage(
                        ViewType.PRIVATE_CONTRACTED_VIEW,
                        160,
                        350,
                        300,
                        5544,
                        66500,
                        5433
                    )
                )
            ),
            // Different style is different aggregation
            NotificationMemoryUsage(
                "package 1",
                384,
                "key2",
                Notification.Builder(context).setStyle(Notification.BigTextStyle()).build(),
                NotificationObjectUsage(
                    77,
                    54,
                    34,
                    NotificationEnums.STYLE_BIG_TEXT,
                    77,
                    432,
                    2342,
                    true
                ),
                listOf(
                    NotificationViewUsage(ViewType.TOTAL, 3245, 1234, 7653, 543, 765, 7655),
                    NotificationViewUsage(
                        ViewType.PRIVATE_CONTRACTED_VIEW,
                        160,
                        350,
                        300,
                        5544,
                        66500,
                        5433
                    )
                )
            ),
            // Different package is also different aggregation
            NotificationMemoryUsage(
                "package 2",
                684,
                "key2",
                Notification.Builder(context).setStyle(Notification.BigPictureStyle()).build(),
                NotificationObjectUsage(
                    32,
                    654,
                    234,
                    NotificationEnums.STYLE_BIG_PICTURE,
                    211,
                    776,
                    435,
                    true
                ),
                listOf(
                    NotificationViewUsage(ViewType.TOTAL, 4355, 6543, 4322, 5435, 6546, 65485),
                    NotificationViewUsage(
                        ViewType.PRIVATE_CONTRACTED_VIEW,
                        6546,
                        7657,
                        4353,
                        6546,
                        76575,
                        54654
                    )
                )
            )
        )
}
