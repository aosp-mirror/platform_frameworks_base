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

package com.android.keyguard

import android.app.ActivityTaskManager
import android.content.pm.PackageManager
import android.os.PowerManager
import android.platform.test.annotations.EnableFlags
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.haptics.msdl.fakeMSDLPlayer
import com.android.systemui.haptics.msdl.msdlPlayer
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.testKosmos
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.android.msdl.data.model.MSDLToken
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class EmergencyButtonControllerTest : SysuiTestCase() {
    @Mock lateinit var emergencyButton: EmergencyButton
    @Mock lateinit var configurationController: ConfigurationController
    @Mock lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock lateinit var telephonyManager: TelephonyManager
    @Mock lateinit var powerManager: PowerManager
    @Mock lateinit var activityTaskManager: ActivityTaskManager
    @Mock lateinit var shadeController: ShadeController
    @Mock lateinit var telecomManager: TelecomManager
    @Mock lateinit var metricsLogger: MetricsLogger
    @Mock lateinit var lockPatternUtils: LockPatternUtils
    @Mock lateinit var packageManager: PackageManager
    @Mock lateinit var mSelectedUserInteractor: SelectedUserInteractor

    val fakeSystemClock = FakeSystemClock()
    val mainExecutor = FakeExecutor(fakeSystemClock)
    val backgroundExecutor = FakeExecutor(fakeSystemClock)
    private val kosmos = testKosmos()
    private val msdlPlayer = kosmos.fakeMSDLPlayer

    lateinit var underTest: EmergencyButtonController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            EmergencyButtonController(
                emergencyButton,
                configurationController,
                keyguardUpdateMonitor,
                powerManager,
                activityTaskManager,
                shadeController,
                telecomManager,
                metricsLogger,
                lockPatternUtils,
                mainExecutor,
                backgroundExecutor,
                mSelectedUserInteractor,
                msdlPlayer,
            )
        context.setMockPackageManager(packageManager)
        Mockito.`when`(emergencyButton.context).thenReturn(context)
    }

    @Test
    fun testUpdateEmergencyButton() {
        Mockito.`when`(telecomManager.isInCall).thenReturn(true)
        Mockito.`when`(lockPatternUtils.isSecure(anyInt())).thenReturn(true)
        Mockito.`when`(packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            .thenReturn(true)
        underTest.updateEmergencyCallButton()
        backgroundExecutor.runAllReady()
        verify(emergencyButton, never())
            .updateEmergencyCallButton(
                /* isInCall= */ any(),
                /* hasTelephonyRadio= */ any(),
                /* simLocked= */ any(),
                /* isSecure= */ any()
            )
        mainExecutor.runAllReady()
        verify(emergencyButton)
            .updateEmergencyCallButton(
                /* isInCall= */ eq(true),
                /* hasTelephonyRadio= */ eq(true),
                /* simLocked= */ any(),
                /* isSecure= */ eq(true)
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun takeEmergencyCallAction_withMSDLFeedback_playsEmergencyButtonTokenAndNullAttributes() {
        underTest.takeEmergencyCallAction()

        assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.KEYPRESS_RETURN)
        assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
    }
}
