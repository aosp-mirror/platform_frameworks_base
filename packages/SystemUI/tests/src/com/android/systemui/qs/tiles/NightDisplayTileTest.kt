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

import android.hardware.display.ColorDisplayManager
import android.hardware.display.NightDisplayListener
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.dagger.NightDisplayListenerModule
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.policy.LocationController
import com.google.common.truth.Truth
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class NightDisplayTileTest : SysuiTestCase() {
    @Mock private lateinit var mHost: QSHost

    @Mock private lateinit var mMetricsLogger: MetricsLogger

    @Mock private lateinit var mStatusBarStateController: StatusBarStateController

    @Mock private lateinit var mActivityStarter: ActivityStarter

    @Mock private lateinit var mQsLogger: QSLogger

    @Mock private lateinit var mLocationController: LocationController

    @Mock private lateinit var mColorDisplayManager: ColorDisplayManager

    @Mock private lateinit var mNightDisplayListenerBuilder: NightDisplayListenerModule.Builder

    @Mock private lateinit var mNightDisplayListener: NightDisplayListener

    @Mock private lateinit var mUiEventLogger: QsEventLogger

    private lateinit var mTestableLooper: TestableLooper
    private lateinit var mTile: NightDisplayTile



    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mTestableLooper = TestableLooper.get(this)
        whenever(mHost.context).thenReturn(mContext)
        whenever(mHost.userContext).thenReturn(mContext)
        whenever(mNightDisplayListenerBuilder.setUser(anyInt()))
            .thenReturn(mNightDisplayListenerBuilder)
        whenever(mNightDisplayListenerBuilder.build()).thenReturn(mNightDisplayListener)

        mTile =
            NightDisplayTile(
                mHost,
                mUiEventLogger,
                mTestableLooper.looper,
                Handler(mTestableLooper.looper),
                FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQsLogger,
                mLocationController,
                mColorDisplayManager,
                mNightDisplayListenerBuilder
            )
    }

    @After
    fun tearDown() {
        mTile.destroy()
        mTestableLooper.processAllMessages()
    }

    @Test
    fun testIcon_whenDisabled_showsOffState() {
        whenever(mColorDisplayManager.isNightDisplayActivated).thenReturn(false)
        val state = QSTile.BooleanState()

        mTile.handleUpdateState(state, /* arg= */ null)

        Truth.assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_nightlight_icon_off))
    }

    @Test
    fun testIcon_whenEnabled_showsOnState() {
        whenever(mColorDisplayManager.isNightDisplayActivated).thenReturn(true)
        val state = QSTile.BooleanState()

        mTile.handleUpdateState(state, /* arg= */ null)

        Truth.assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_nightlight_icon_on))
    }
}
