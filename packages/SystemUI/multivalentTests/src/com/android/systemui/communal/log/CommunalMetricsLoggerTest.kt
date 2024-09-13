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

package com.android.systemui.communal.log

import android.util.StatsEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.shared.log.CommunalMetricsLogger
import com.android.systemui.shared.system.SysUiStatsLog
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalMetricsLoggerTest : SysuiTestCase() {
    private val statsLogProxy = mock<CommunalMetricsLogger.StatsLogProxy>()

    private val loggablePrefixes = listOf("com.blue.", "com.red.")
    private lateinit var underTest: CommunalMetricsLogger

    @Before
    fun setUp() {
        underTest = CommunalMetricsLogger(loggablePrefixes, statsLogProxy)
    }

    @Test
    fun logAddWidget_componentNotLoggable_doNotLog() {
        underTest.logAddWidget(
            componentName = "com.green.package/my_test_widget",
            rank = 1,
        )
        verify(statsLogProxy, never())
            .writeCommunalHubWidgetEventReported(anyInt(), any(), anyInt())
    }

    @Test
    fun logAddWidget_componentLoggable_logAddEvent() {
        underTest.logAddWidget(
            componentName = "com.blue.package/my_test_widget",
            rank = 1,
        )
        verify(statsLogProxy)
            .writeCommunalHubWidgetEventReported(
                SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED__ACTION__ADD,
                "com.blue.package/my_test_widget",
                1,
            )
    }

    @Test
    fun logRemoveWidget_componentNotLoggable_doNotLog() {
        underTest.logRemoveWidget(
            componentName = "com.yellow.package/my_test_widget",
            rank = 2,
        )
        verify(statsLogProxy, never())
            .writeCommunalHubWidgetEventReported(anyInt(), any(), anyInt())
    }

    @Test
    fun logRemoveWidget_componentLoggable_logRemoveEvent() {
        underTest.logRemoveWidget(
            componentName = "com.red.package/my_test_widget",
            rank = 2,
        )
        verify(statsLogProxy)
            .writeCommunalHubWidgetEventReported(
                SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED__ACTION__REMOVE,
                "com.red.package/my_test_widget",
                2,
            )
    }

    @Test
    fun logTapWidget_componentNotLoggable_doNotLog() {
        underTest.logTapWidget(
            componentName = "com.yellow.package/my_test_widget",
            rank = 2,
        )
        verify(statsLogProxy, never())
            .writeCommunalHubWidgetEventReported(anyInt(), any(), anyInt())
    }

    @Test
    fun logTapWidget_componentLoggable_logRemoveEvent() {
        underTest.logTapWidget(
            componentName = "com.red.package/my_test_widget",
            rank = 2,
        )
        verify(statsLogProxy)
            .writeCommunalHubWidgetEventReported(
                SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED__ACTION__TAP,
                "com.red.package/my_test_widget",
                2,
            )
    }

    @Test
    fun logWidgetsSnapshot_logOnlyLoggableComponents() {
        val statsEvents = mutableListOf<StatsEvent>()
        underTest.logWidgetsSnapshot(
            statsEvents,
            listOf(
                "com.blue.package/my_test_widget_1",
                "com.green.package/my_test_widget_2",
                "com.red.package/my_test_widget_3",
                "com.yellow.package/my_test_widget_4",
            ),
        )
        verify(statsLogProxy)
            .buildCommunalHubSnapshotStatsEvent(
                componentNames =
                    arrayOf(
                        "com.blue.package/my_test_widget_1",
                        "com.red.package/my_test_widget_3",
                    ),
                widgetCount = 4,
            )
        assertThat(statsEvents).hasSize(1)
    }
}
