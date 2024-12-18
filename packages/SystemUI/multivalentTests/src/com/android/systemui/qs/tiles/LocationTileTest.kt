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
 * limitations under the License
 */

package com.android.systemui.qs.tiles

import android.content.Context
import android.os.Handler
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class LocationTileTest : SysuiTestCase() {

    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var qsLogger: QSLogger
    @Mock
    private lateinit var qsHost: QSHost
    @Mock
    private lateinit var metricsLogger: MetricsLogger
    private val falsingManager = FalsingManagerFake()
    @Mock
    private lateinit var statusBarStateController: StatusBarStateController
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var locationController: LocationController
    @Mock
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var panelInteractor: PanelInteractor
    @Mock
    private lateinit var uiEventLogger: QsEventLogger

    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: LocationTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        `when`(qsHost.context).thenReturn(mockContext)

        tile = LocationTile(
            qsHost,
            uiEventLogger,
            testableLooper.looper,
            Handler(testableLooper.looper),
            falsingManager,
            metricsLogger,
            statusBarStateController,
            activityStarter,
            qsLogger,
            locationController,
            keyguardStateController,
            panelInteractor,
        )
    }

    @After
    fun tearDown() {
        tile.destroy()
        testableLooper.processAllMessages()
    }

    @Test
    fun testIcon_whenDisabled_isOffState() {
        val state = QSTile.BooleanState()
        `when`(locationController.isLocationEnabled).thenReturn(false)

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_location_icon_off))
    }

    @Test
    fun testIcon_whenEnabled_isOnState() {
        val state = QSTile.BooleanState()
        `when`(locationController.isLocationEnabled).thenReturn(true)

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_location_icon_on))
    }

    @Test
    fun testClickWhenLockedWillCallOpenPanels() {
        `when`(keyguardStateController.isMethodSecure).thenReturn(true)
        `when`(keyguardStateController.isShowing).thenReturn(true)

        tile.handleClick(null)

        val captor = argumentCaptor<Runnable>()
        verify(activityStarter).postQSRunnableDismissingKeyguard(capture(captor))
        captor.value.run()

        verify(panelInteractor).openPanels()
    }
}
