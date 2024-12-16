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

import android.os.Handler
import android.platform.test.flag.junit.FlagsParameterization
import android.platform.test.flag.junit.FlagsParameterization.allCombinationsOf
import android.provider.Settings
import android.safetycenter.SafetyCenterManager
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.flags.QSComposeFragment
import com.android.systemui.qs.flags.QsInCompose.isEnabled
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIconWithRes
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@RunWith(ParameterizedAndroidJunit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class MicrophoneToggleTileTest(flags: FlagsParameterization) : SysuiTestCase() {
    companion object {
        /* isBlocked */
        const val MICROPHONE_TOGGLE_ENABLED: Boolean = false
        const val MICROPHONE_TOGGLE_DISABLED: Boolean = true

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return allCombinationsOf(QSComposeFragment.FLAG_NAME)
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Mock private lateinit var host: QSHost
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var privacyController: IndividualSensorPrivacyController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var uiEventLogger: QsEventLogger
    @Mock private lateinit var safetyCenterManager: SafetyCenterManager

    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: MicrophoneToggleTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        whenever(host.context).thenReturn(mContext)

        tile =
            MicrophoneToggleTile(
                host,
                uiEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                metricsLogger,
                FalsingManagerFake(),
                statusBarStateController,
                activityStarter,
                qsLogger,
                privacyController,
                keyguardStateController,
                safetyCenterManager,
            )
    }

    @After
    fun tearDown() {
        tile.destroy()
        testableLooper.processAllMessages()
    }

    @Test
    fun testIcon_whenMicrophoneAccessEnabled_isOnState() {
        val state = QSTile.BooleanState()

        tile.handleUpdateState(state, MICROPHONE_TOGGLE_ENABLED)

        assertThat(state.icon).isEqualTo(createExpectedIcon(R.drawable.qs_mic_access_on))
    }

    @Test
    fun testIcon_whenMicrophoneAccessDisabled_isOffState() {
        val state = QSTile.BooleanState()

        tile.handleUpdateState(state, MICROPHONE_TOGGLE_DISABLED)

        assertThat(state.icon).isEqualTo(createExpectedIcon(R.drawable.qs_mic_access_off))
    }

    @Test
    fun testLongClickIntent_safetyCenterEnabled() {
        whenever(safetyCenterManager.isSafetyCenterEnabled).thenReturn(true)
        val micTile =
            MicrophoneToggleTile(
                host,
                uiEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                metricsLogger,
                FalsingManagerFake(),
                statusBarStateController,
                activityStarter,
                qsLogger,
                privacyController,
                keyguardStateController,
                safetyCenterManager,
            )
        assertThat(micTile.longClickIntent?.action).isEqualTo(Settings.ACTION_PRIVACY_CONTROLS)
        micTile.destroy()
        testableLooper.processAllMessages()
    }

    @Test
    fun testLongClickIntent_safetyCenterDisabled() {
        whenever(safetyCenterManager.isSafetyCenterEnabled).thenReturn(false)
        val micTile =
            MicrophoneToggleTile(
                host,
                uiEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                metricsLogger,
                FalsingManagerFake(),
                statusBarStateController,
                activityStarter,
                qsLogger,
                privacyController,
                keyguardStateController,
                safetyCenterManager,
            )
        assertThat(micTile.longClickIntent?.action).isEqualTo(Settings.ACTION_PRIVACY_SETTINGS)
        micTile.destroy()
        testableLooper.processAllMessages()
    }

    private fun createExpectedIcon(resId: Int): QSTile.Icon {
        return if (isEnabled) {
            DrawableIconWithRes(mContext.getDrawable(resId), resId)
        } else {
            QSTileImpl.ResourceIcon.get(resId)
        }
    }
}
