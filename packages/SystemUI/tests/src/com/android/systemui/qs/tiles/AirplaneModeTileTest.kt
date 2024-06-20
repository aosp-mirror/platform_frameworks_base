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

import android.net.ConnectivityManager
import android.os.Handler
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.telephony.flags.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.GlobalSettings
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class AirplaneModeTileTest : SysuiTestCase() {

    @Mock
    private lateinit var mHost: QSHost
    @Mock
    private lateinit var mMetricsLogger: MetricsLogger
    @Mock
    private lateinit var mStatusBarStateController: StatusBarStateController
    @Mock
    private lateinit var mActivityStarter: ActivityStarter
    @Mock
    private lateinit var mQsLogger: QSLogger
    @Mock
    private lateinit var mBroadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var mLazyConnectivityManager: Lazy<ConnectivityManager>
    @Mock
    private lateinit var mConnectivityManager: ConnectivityManager
    @Mock
    private lateinit var mGlobalSettings: GlobalSettings
    @Mock
    private lateinit var mUserTracker: UserTracker
    @Mock
    private lateinit var mUiEventLogger: QsEventLogger
    private lateinit var mTestableLooper: TestableLooper
    private lateinit var mTile: AirplaneModeTile

    @Mock
    private lateinit var mClickJob: Job
    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mTestableLooper = TestableLooper.get(this)
        Mockito.`when`(mHost.context).thenReturn(mContext)
        Mockito.`when`(mHost.userContext).thenReturn(mContext)
        Mockito.`when`(mLazyConnectivityManager.get()).thenReturn(mConnectivityManager)
        mTile = AirplaneModeTile(
            mHost,
            mUiEventLogger,
            mTestableLooper.looper,
            Handler(mTestableLooper.looper),
            FalsingManagerFake(),
            mMetricsLogger,
            mStatusBarStateController,
            mActivityStarter,
            mQsLogger,
            mBroadcastDispatcher,
            mLazyConnectivityManager,
            mGlobalSettings,
            mUserTracker)
    }

    @After
    fun tearDown() {
        mTile.destroy()
        mTestableLooper.processAllMessages()
    }

    @Test
    fun testIcon_whenDisabled_showsOffState() {
        val state = QSTile.BooleanState()

        mTile.handleUpdateState(state, 0)

        assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_airplane_icon_off))
    }

    @Test
    fun testIcon_whenEnabled_showsOnState() {
        val state = QSTile.BooleanState()

        mTile.handleUpdateState(state, 1)

        assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_airplane_icon_on))
    }

    @Test
    fun handleClick_noSatelliteFeature_directSetAirplaneMode() {
        mSetFlagsRule.disableFlags(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)

        mTile.handleClick(null)

        verify(mConnectivityManager).setAirplaneMode(any())
    }

    @Test
    fun handleClick_hasSatelliteFeatureButClickIsProcessing_doNothing() {
        mSetFlagsRule.enableFlags(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        Mockito.`when`(mClickJob.isCompleted).thenReturn(false)
        mTile.mClickJob = mClickJob

        mTile.handleClick(null)

        verify(mConnectivityManager, times(0)).setAirplaneMode(any())
    }
}
