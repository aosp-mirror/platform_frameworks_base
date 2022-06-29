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

package com.android.systemui.qs.tiles

import android.content.Context
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.SecureSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class BatterySaverTileTest : SysuiTestCase() {

    companion object {
        private const val USER = 10
    }

    @Mock
    private lateinit var userContext: Context
    @Mock
    private lateinit var qsHost: QSHost
    @Mock
    private lateinit var metricsLogger: MetricsLogger
    @Mock
    private lateinit var statusBarStateController: StatusBarStateController
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var qsLogger: QSLogger
    @Mock
    private lateinit var batteryController: BatteryController
    @Mock
    private lateinit var view: View
    private lateinit var secureSettings: SecureSettings
    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: BatterySaverTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        `when`(qsHost.userContext).thenReturn(userContext)
        `when`(userContext.userId).thenReturn(USER)

        secureSettings = FakeSettings()

        tile = BatterySaverTile(
                qsHost,
                testableLooper.looper,
                Handler(testableLooper.looper),
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                batteryController,
                secureSettings)

        tile.initialize()
        testableLooper.processAllMessages()
    }

    @Test
    fun testSettingWithCorrectUser() {
        assertEquals(USER, tile.mSetting.currentUser)
    }

    @Test
    fun testSettingChangesUser() {
        tile.userSwitch(USER + 1)

        testableLooper.processAllMessages()

        assertEquals(USER + 1, tile.mSetting.currentUser)
    }

    @Test
    fun testClickingPowerSavePassesView() {
        tile.onPowerSaveChanged(true)
        tile.handleClick(view)

        tile.onPowerSaveChanged(false)
        tile.handleClick(view)

        verify(batteryController).setPowerSaveMode(true, view)
        verify(batteryController).setPowerSaveMode(false, view)
    }

    @Test
    fun testStopListeningClearsViewInController() {
        clearInvocations(batteryController)
        tile.handleSetListening(true)
        verify(batteryController, never()).clearLastPowerSaverStartView()

        tile.handleSetListening(false)
        verify(batteryController).clearLastPowerSaverStartView()
    }
}