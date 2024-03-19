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
 */

package com.android.systemui.deviceentry.domain.ui.binder

import android.content.packageManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.TriggerEventListener
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.deviceentry.ui.binder.liftToRunFaceAuthBinder
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.sensors.asyncSensorManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class LiftToRunFaceAuthBinderTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sensorManager = kosmos.asyncSensorManager
    private val powerRepository = kosmos.fakePowerRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val bouncerRepository = kosmos.keyguardBouncerRepository
    private val biometricSettingsRepository = kosmos.biometricSettingsRepository
    private val packageManager = kosmos.packageManager

    @Captor private lateinit var triggerEventListenerCaptor: ArgumentCaptor<TriggerEventListener>
    @Mock private lateinit var mockSensor: Sensor

    private val underTest by lazy { kosmos.liftToRunFaceAuthBinder }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true)
        whenever(sensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE)).thenReturn(mockSensor)
    }

    @Test
    fun doNotListenForGesture() =
        testScope.runTest {
            start()
            verifyNeverRequestsTriggerSensor()
        }

    @Test
    fun awakeKeyguard_listenForGesture() =
        testScope.runTest {
            start()
            givenAwakeKeyguard(true)
            runCurrent()
            verifyRequestTriggerSensor()
        }

    @Test
    fun faceNotEnrolled_listenForGesture() =
        testScope.runTest {
            start()
            givenAwakeKeyguard(true)
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            runCurrent()
            verifyNeverRequestsTriggerSensor()
        }

    @Test
    fun notInteractive_doNotListenForGesture() =
        testScope.runTest {
            start()
            givenAwakeKeyguard(true)
            powerRepository.setInteractive(false)
            runCurrent()
            verifyNeverRequestsTriggerSensor()
        }

    @Test
    fun primaryBouncer_listenForGesture() =
        testScope.runTest {
            start()
            givenAwakeKeyguard(false)
            givenPrimaryBouncerShowing()
            runCurrent()
            verifyRequestTriggerSensor()
        }

    @Test
    fun alternateBouncer_listenForGesture() =
        testScope.runTest {
            start()
            givenAwakeKeyguard(false)
            givenAlternateBouncerShowing()
            runCurrent()
            verifyRequestTriggerSensor()
        }

    @Test
    fun restartListeningForGestureAfterSensorTrigger() =
        testScope.runTest {
            start()
            givenAwakeKeyguard(true)
            runCurrent()
            verifyRequestTriggerSensor()
            clearInvocations(sensorManager)

            triggerEventListenerCaptor.value.onTrigger(null)
            runCurrent()
            verifyRequestTriggerSensor()
        }

    @Test
    fun cancelTriggerSensor_keyguardNotAwakeAnymore() =
        testScope.runTest {
            start()
            givenAwakeKeyguard(true)
            runCurrent()
            verifyRequestTriggerSensor()

            givenAwakeKeyguard(false)
            runCurrent()
            verifyCancelTriggerSensor()
        }

    private fun start() {
        underTest.start()
        biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
        givenAwakeKeyguard(false)
        givenBouncerNotShowing()
    }

    private fun givenAwakeKeyguard(isAwake: Boolean) {
        powerRepository.setInteractive(isAwake)
        keyguardRepository.setKeyguardShowing(isAwake)
        keyguardRepository.setKeyguardOccluded(false)
    }

    private fun givenPrimaryBouncerShowing() {
        bouncerRepository.setPrimaryShow(true)
        bouncerRepository.setAlternateVisible(false)
    }

    private fun givenBouncerNotShowing() {
        bouncerRepository.setPrimaryShow(false)
        bouncerRepository.setAlternateVisible(false)
    }

    private fun givenAlternateBouncerShowing() {
        bouncerRepository.setPrimaryShow(false)
        bouncerRepository.setAlternateVisible(true)
    }

    private fun verifyRequestTriggerSensor() {
        verify(sensorManager).requestTriggerSensor(capture(triggerEventListenerCaptor), any())
    }

    private fun verifyNeverRequestsTriggerSensor() {
        verify(sensorManager, never()).requestTriggerSensor(any(), any())
    }

    private fun verifyCancelTriggerSensor() {
        verify(sensorManager).cancelTriggerSensor(any(), any())
    }
}
