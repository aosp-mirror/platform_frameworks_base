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

package com.android.systemui.biometrics

import android.content.Context
import android.hardware.biometrics.SensorProperties
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.view.ViewGroup.LayoutParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenEver
import org.mockito.junit.MockitoJUnit

private const val SENSOR_ID = 1

@SmallTest
@RunWith(AndroidJUnit4::class)
class AuthBiometricFingerprintIconControllerTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var iconView: LottieAnimationView
    @Mock private lateinit var iconViewOverlay: LottieAnimationView
    @Mock private lateinit var layoutParam: LayoutParams
    @Mock private lateinit var fingerprintManager: FingerprintManager

    private lateinit var controller: AuthBiometricFingerprintIconController

    @Before
    fun setUp() {
        context.addMockSystemService(Context.FINGERPRINT_SERVICE, fingerprintManager)
        whenEver(iconView.layoutParams).thenReturn(layoutParam)
        whenEver(iconViewOverlay.layoutParams).thenReturn(layoutParam)
    }

    @Test
    fun testIconContentDescription_SfpsDevice() {
        setupFingerprintSensorProperties(FingerprintSensorProperties.TYPE_POWER_BUTTON)
        controller = AuthBiometricFingerprintIconController(context, iconView, iconViewOverlay)

        assertThat(controller.getIconContentDescription(AuthBiometricView.STATE_AUTHENTICATING))
            .isEqualTo(
                context.resources.getString(
                    R.string.security_settings_sfps_enroll_find_sensor_message
                )
            )
    }

    @Test
    fun testIconContentDescription_NonSfpsDevice() {
        setupFingerprintSensorProperties(FingerprintSensorProperties.TYPE_UDFPS_OPTICAL)
        controller = AuthBiometricFingerprintIconController(context, iconView, iconViewOverlay)

        assertThat(controller.getIconContentDescription(AuthBiometricView.STATE_AUTHENTICATING))
            .isEqualTo(context.resources.getString(R.string.fingerprint_dialog_touch_sensor))
    }

    private fun setupFingerprintSensorProperties(sensorType: Int) {
        whenEver(fingerprintManager.sensorPropertiesInternal)
            .thenReturn(
                listOf(
                    FingerprintSensorPropertiesInternal(
                        SENSOR_ID,
                        SensorProperties.STRENGTH_STRONG,
                        5 /* maxEnrollmentsPerUser */,
                        listOf() /* componentInfo */,
                        sensorType,
                        true /* halControlsIllumination */,
                        true /* resetLockoutRequiresHardwareAuthToken */,
                        listOf() /* sensorLocations */
                    )
                )
            )
    }
}
