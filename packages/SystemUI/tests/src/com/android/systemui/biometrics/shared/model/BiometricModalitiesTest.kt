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

package com.android.systemui.biometrics.shared.model

import android.hardware.fingerprint.FingerprintSensorProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.faceSensorPropertiesInternal
import com.android.systemui.biometrics.fingerprintSensorPropertiesInternal
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BiometricModalitiesTest : SysuiTestCase() {

    @Test
    fun isEmpty() {
        assertThat(BiometricModalities().isEmpty).isTrue()
    }

    @Test
    fun hasUdfps() {
        with(
            BiometricModalities(
                fingerprintProperties = fingerprintSensorPropertiesInternal(
                    sensorType = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL
                ).first(),
            )
        ) {
            assertThat(isEmpty).isFalse()
            assertThat(hasUdfps).isTrue()
            assertThat(hasSfps).isFalse()
            assertThat(hasFace).isFalse()
            assertThat(hasFaceOnly).isFalse()
            assertThat(hasFingerprint).isTrue()
            assertThat(hasFingerprintOnly).isTrue()
            assertThat(hasFaceAndFingerprint).isFalse()
        }
    }

    @Test
    fun hasSfps() {
        with(
            BiometricModalities(
                fingerprintProperties = fingerprintSensorPropertiesInternal(
                    sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                ).first(),
            )
        ) {
            assertThat(isEmpty).isFalse()
            assertThat(hasUdfps).isFalse()
            assertThat(hasSfps).isTrue()
            assertThat(hasFace).isFalse()
            assertThat(hasFaceOnly).isFalse()
            assertThat(hasFingerprint).isTrue()
            assertThat(hasFingerprintOnly).isTrue()
            assertThat(hasFaceAndFingerprint).isFalse()
        }
    }

    @Test
    fun fingerprintOnly() {
        with(
            BiometricModalities(
                fingerprintProperties = fingerprintSensorPropertiesInternal().first(),
            )
        ) {
            assertThat(isEmpty).isFalse()
            assertThat(hasFace).isFalse()
            assertThat(hasFaceOnly).isFalse()
            assertThat(hasFingerprint).isTrue()
            assertThat(hasFingerprintOnly).isTrue()
            assertThat(hasFaceAndFingerprint).isFalse()
        }
    }

    @Test
    fun faceOnly() {
        with(BiometricModalities(faceProperties = faceSensorPropertiesInternal().first())) {
            assertThat(isEmpty).isFalse()
            assertThat(hasFace).isTrue()
            assertThat(hasFaceOnly).isTrue()
            assertThat(hasFingerprint).isFalse()
            assertThat(hasFingerprintOnly).isFalse()
            assertThat(hasFaceAndFingerprint).isFalse()
        }
    }

    @Test
    fun faceStrength() {
        with(
            BiometricModalities(
                fingerprintProperties = fingerprintSensorPropertiesInternal(strong = false).first(),
                faceProperties = faceSensorPropertiesInternal(strong = true).first()
            )
        ) {
            assertThat(isFaceStrong).isTrue()
        }

        with(
            BiometricModalities(
                fingerprintProperties = fingerprintSensorPropertiesInternal(strong = false).first(),
                faceProperties = faceSensorPropertiesInternal(strong = false).first()
            )
        ) {
            assertThat(isFaceStrong).isFalse()
        }
    }

    @Test
    fun faceAndFingerprint() {
        with(
            BiometricModalities(
                fingerprintProperties = fingerprintSensorPropertiesInternal().first(),
                faceProperties = faceSensorPropertiesInternal().first(),
            )
        ) {
            assertThat(isEmpty).isFalse()
            assertThat(hasFace).isTrue()
            assertThat(hasFingerprint).isTrue()
            assertThat(hasFaceOnly).isFalse()
            assertThat(hasFingerprintOnly).isFalse()
            assertThat(hasFaceAndFingerprint).isTrue()
        }
    }
}
