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

package com.android.systemui.accessibility

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.AccessibilityLogger.MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_CLOSED
import com.android.systemui.accessibility.AccessibilityLogger.MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_OPENED
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AccessibilityLoggerTest : SysuiTestCase() {
    @JvmField @Rule val mockito = MockitoJUnit.rule()

    private val fakeClock = FakeSystemClock()
    @Mock private lateinit var fakeLogger: UiEventLogger

    private lateinit var a11yLogger: AccessibilityLogger

    @Before
    fun setup() {
        a11yLogger = AccessibilityLogger(fakeLogger, fakeClock)
    }

    @Test
    fun logThrottled_onceWithinWindow() {
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)
        fakeClock.advanceTime(100L)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)
        fakeClock.advanceTime(900L)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)
        fakeClock.advanceTime(1100L)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)

        verify(fakeLogger, times(2)).log(eq(MAGNIFICATION_SETTINGS_PANEL_OPENED))
    }

    @Test
    fun logThrottled_interlacedLogsAllWithinWindow() {
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_CLOSED, 1000)
        fakeClock.advanceTime(100L)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_CLOSED, 1000)
        fakeClock.advanceTime(200L)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)
        fakeClock.advanceTime(1100L)
        a11yLogger.logThrottled(MAGNIFICATION_SETTINGS_PANEL_OPENED, 1000)

        verify(fakeLogger, times(3)).log(eq(MAGNIFICATION_SETTINGS_PANEL_OPENED))
        verify(fakeLogger).log(eq(MAGNIFICATION_SETTINGS_PANEL_CLOSED))
    }
}
