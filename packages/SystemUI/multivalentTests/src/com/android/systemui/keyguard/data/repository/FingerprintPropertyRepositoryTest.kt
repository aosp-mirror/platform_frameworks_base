/*
 * Copyright (C) 2024 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.repository

import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepositoryImpl
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@SmallTest
class FingerprintPropertyRepositoryTest : SysuiTestCase() {
    @JvmField @Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val testScope = TestScope()
    private lateinit var underTest: FingerprintPropertyRepositoryImpl
    @Mock private lateinit var fingerprintManager: FingerprintManager
    @Captor
    private lateinit var fingerprintCallbackCaptor:
        ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback>

    @Before
    fun setup() {
        underTest =
            FingerprintPropertyRepositoryImpl(
                testScope.backgroundScope,
                Dispatchers.Main.immediate,
                fingerprintManager,
            )
    }

    @Test
    fun propertiesInitialized_onStartFalse() =
        testScope.runTest {
            val propertiesInitialized by collectLastValue(underTest.propertiesInitialized)
            assertThat(propertiesInitialized).isFalse()
        }

    @Test
    fun propertiesInitialized_onStartTrue() =
        testScope.runTest {
            //            // collect sensorType to update fingerprintCallback before
            // propertiesInitialized
            //            // is listened for
            val sensorType by collectLastValue(underTest.sensorType)
            runCurrent()
            captureFingerprintCallback()

            fingerprintCallbackCaptor.value.onAllAuthenticatorsRegistered(emptyList())
            val propertiesInitialized by collectLastValue(underTest.propertiesInitialized)
            assertThat(propertiesInitialized).isTrue()
        }

    @Test
    fun propertiesInitialized_updatedToTrue() =
        testScope.runTest {
            val propertiesInitialized by collectLastValue(underTest.propertiesInitialized)
            assertThat(propertiesInitialized).isFalse()

            captureFingerprintCallback()
            fingerprintCallbackCaptor.value.onAllAuthenticatorsRegistered(emptyList())
            assertThat(propertiesInitialized).isTrue()
        }

    private fun captureFingerprintCallback() {
        verify(fingerprintManager)
            .addAuthenticatorsRegisteredCallback(fingerprintCallbackCaptor.capture())
    }
}
