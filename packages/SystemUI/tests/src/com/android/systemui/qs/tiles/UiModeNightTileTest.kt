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

package com.android.systemui.qs.tiles

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSTileHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.LocationController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class UiModeNightTileTest : SysuiTestCase() {

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var uiModeManager: UiModeManager
    @Mock private lateinit var resources: Resources
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var qsHost: QSTileHost
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var batteryController: BatteryController
    @Mock private lateinit var locationController: LocationController

    private val uiEventLogger = UiEventLoggerFake()
    private val falsingManager = FalsingManagerFake()
    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: UiModeNightTile
    private lateinit var configuration: Configuration

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        configuration = Configuration()
        mContext.addMockSystemService(UiModeManager::class.java, uiModeManager)

        `when`(qsHost.context).thenReturn(mockContext)
        `when`(qsHost.userContext).thenReturn(mContext)
        `when`(mockContext.resources).thenReturn(resources)
        `when`(resources.configuration).thenReturn(configuration)
        `when`(qsHost.uiEventLogger).thenReturn(uiEventLogger)

        tile =
            UiModeNightTile(
                qsHost,
                testableLooper.looper,
                Handler(testableLooper.looper),
                falsingManager,
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                configurationController,
                batteryController,
                locationController
            )
    }

    @Test
    fun testIcon_whenNightModeOn_isOnState() {
        val state = QSTile.BooleanState()
        setNightModeOn()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_light_dark_theme_icon_on))
    }

    @Test
    fun testIcon_whenNightModeOn_isOffState() {
        val state = QSTile.BooleanState()
        setNightModeOff()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_light_dark_theme_icon_off))
    }

    private fun setNightModeOn() {
        `when`(uiModeManager.nightMode).thenReturn(UiModeManager.MODE_NIGHT_YES)
        configuration.uiMode = Configuration.UI_MODE_NIGHT_YES
    }

    private fun setNightModeOff() {
        `when`(uiModeManager.nightMode).thenReturn(UiModeManager.MODE_NIGHT_NO)
        configuration.uiMode = Configuration.UI_MODE_NIGHT_NO
    }
}
