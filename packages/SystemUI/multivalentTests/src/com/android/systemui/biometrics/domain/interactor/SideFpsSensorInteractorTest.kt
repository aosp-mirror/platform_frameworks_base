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

package com.android.systemui.biometrics.domain.interactor

import android.graphics.Rect
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.display.DisplayManagerGlobal
import android.view.Display
import android.view.DisplayInfo
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.DisplayRotation.ROTATION_0
import com.android.systemui.biometrics.shared.model.DisplayRotation.ROTATION_180
import com.android.systemui.biometrics.shared.model.DisplayRotation.ROTATION_270
import com.android.systemui.biometrics.shared.model.DisplayRotation.ROTATION_90
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.SideFpsLogger
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class SideFpsSensorInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()
    private val testScope = kosmos.testScope

    private val fingerprintRepository = FakeFingerprintPropertyRepository()

    private lateinit var underTest: SideFpsSensorInteractor

    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var displayStateInteractor: DisplayStateInteractor
    @Mock
    private lateinit var fingerprintInteractiveToAuthProvider: FingerprintInteractiveToAuthProvider
    private val isRestToUnlockEnabled = MutableStateFlow(false)
    private val contextDisplayInfo = DisplayInfo()
    private val displayChangeEvent = MutableStateFlow(0)
    private val currentRotation = MutableStateFlow(ROTATION_0)

    @Before
    fun setup() {
        mContext = spy(mContext)

        val resources = mContext.resources
        whenever(mContext.display)
            .thenReturn(
                Display(mock(DisplayManagerGlobal::class.java), 1, contextDisplayInfo, resources)
            )
        whenever(displayStateInteractor.displayChanges).thenReturn(displayChangeEvent)
        whenever(displayStateInteractor.currentRotation).thenReturn(currentRotation)

        contextDisplayInfo.uniqueId = "current-display"
        whenever(fingerprintInteractiveToAuthProvider.enabledForCurrentUser)
            .thenReturn(isRestToUnlockEnabled)
        overrideResource(R.bool.config_restToUnlockSupported, true)
        underTest =
            SideFpsSensorInteractor(
                mContext,
                fingerprintRepository,
                windowManager,
                displayStateInteractor,
                Optional.of(fingerprintInteractiveToAuthProvider),
                kosmos.keyguardTransitionInteractor,
                SideFpsLogger(logcatLogBuffer("SfpsLogger"))
            )
    }

    @Test
    fun testSfpsSensorAvailable() =
        testScope.runTest {
            val isAvailable by collectLastValue(underTest.isAvailable)

            setupFingerprint(FingerprintSensorType.POWER_BUTTON)
            assertThat(isAvailable).isTrue()

            setupFingerprint(FingerprintSensorType.HOME_BUTTON)
            assertThat(isAvailable).isFalse()

            setupFingerprint(FingerprintSensorType.REAR)
            assertThat(isAvailable).isFalse()

            setupFingerprint(FingerprintSensorType.UDFPS_OPTICAL)
            assertThat(isAvailable).isFalse()

            setupFingerprint(FingerprintSensorType.UDFPS_ULTRASONIC)
            assertThat(isAvailable).isFalse()

            setupFingerprint(FingerprintSensorType.UNKNOWN)
            assertThat(isAvailable).isFalse()
        }

    private suspend fun sendTransition(from: KeyguardState, to: KeyguardState) {
        kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
            listOf(
                TransitionStep(
                    from = from,
                    to = to,
                    transitionState = TransitionState.STARTED,
                ),
                TransitionStep(
                    from = from,
                    to = to,
                    transitionState = TransitionState.FINISHED,
                    value = 1.0f
                )
            ),
            testScope
        )
    }

    @Test
    fun authenticationDurationIsLongerIfScreenIsOff() =
        testScope.runTest {
            val authenticationDuration by collectLastValue(underTest.authenticationDuration)
            val longDuration =
                context.resources.getInteger(R.integer.config_restToUnlockDurationScreenOff)
            sendTransition(LOCKSCREEN, OFF)

            runCurrent()
            assertThat(authenticationDuration).isEqualTo(longDuration)
        }

    @Test
    fun authenticationDurationIsLongerIfScreenIsDozing() =
        testScope.runTest {
            val authenticationDuration by collectLastValue(underTest.authenticationDuration)
            val longDuration =
                context.resources.getInteger(R.integer.config_restToUnlockDurationScreenOff)
            sendTransition(LOCKSCREEN, DOZING)
            runCurrent()
            assertThat(authenticationDuration).isEqualTo(longDuration)
        }

    @Test
    fun authenticationDurationIsShorterIfScreenIsNotDozingOrOff() =
        testScope.runTest {
            val authenticationDuration by collectLastValue(underTest.authenticationDuration)
            val shortDuration =
                context.resources.getInteger(R.integer.config_restToUnlockDurationDefault)
            val allOtherKeyguardStates = KeyguardState.entries.filter { it != OFF && it != DOZING }

            allOtherKeyguardStates.forEach { destinationState ->
                sendTransition(OFF, destinationState)

                runCurrent()
                assertThat(authenticationDuration).isEqualTo(shortDuration)
            }
        }

    @Test
    fun verticalSensorLocationIsAdjustedToScreenPositionForRotation0() =
        testScope.runTest {
            /*
            (0,0)                (1000,0)
               ------------------
              |  ^^^^^           |  (1000, 200)
              |   status bar    || <--- start of sensor at Rotation_0
              |                 || <--- end of sensor
              |                  |  (1000, 300)
              |                  |
               ------------------ (1000, 800)
             */
            setupFPLocationAndDisplaySize(
                width = 1000,
                height = 800,
                rotation = ROTATION_0,
                sensorLocationY = 200,
                sensorWidth = 100,
            )

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation!!.left).isEqualTo(1000)
            assertThat(sensorLocation!!.top).isEqualTo(200)
            assertThat(sensorLocation!!.isSensorVerticalInDefaultOrientation).isEqualTo(true)
            assertThat(sensorLocation!!.length).isEqualTo(100)
        }

    @Test
    fun verticalSensorLocationIsAdjustedToScreenPositionForRotation270() =
        testScope.runTest {
            /*
            (800,0)                     (800, 1000)
                  ---------------------
                 |                     | (600, 1000)
                 |   <                || <--- end of sensor at Rotation_270
                 |   < status bar     || <--- start of sensor
                 |   <                 | (500, 1000)
                 |   <                 |
            (0,0) ---------------------
             */
            setupFPLocationAndDisplaySize(
                width = 800,
                height = 1000,
                rotation = ROTATION_270,
                sensorLocationY = 200,
                sensorWidth = 100,
            )

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation!!.left).isEqualTo(500)
            assertThat(sensorLocation!!.top).isEqualTo(1000)
            assertThat(sensorLocation!!.isSensorVerticalInDefaultOrientation).isEqualTo(true)
            assertThat(sensorLocation!!.length).isEqualTo(100)
        }

    @Test
    fun verticalSensorLocationIsAdjustedToScreenPositionForRotation90() =
        testScope.runTest {
            /*
                                            (0,0)
                       ---------------------
                      |                     | (200, 0)
                      |               >    || <--- end of sensor at Rotation_270
                      |    status bar >    || <--- start of sensor
                      |               >     | (300, 0)
                      |               >     |
            (800,1000) ---------------------
             */
            setupFPLocationAndDisplaySize(
                width = 800,
                height = 1000,
                rotation = ROTATION_90,
                sensorLocationY = 200,
                sensorWidth = 100,
            )

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation!!.left).isEqualTo(200)
            assertThat(sensorLocation!!.top).isEqualTo(0)
            assertThat(sensorLocation!!.isSensorVerticalInDefaultOrientation).isEqualTo(true)
            assertThat(sensorLocation!!.length).isEqualTo(100)
        }

    @Test
    fun verticalSensorLocationIsAdjustedToScreenPositionForRotation180() =
        testScope.runTest {
            /*

            (1000,800) ---------------------
                      |                     | (0, 600)
                      |                    || <--- end of sensor at Rotation_270
                      |    status bar      || <--- start of sensor
                      |   \/\/\/\/\/\/\/    | (0, 500)
                      |                     |
                       ---------------------  (0,0)
             */
            setupFPLocationAndDisplaySize(
                width = 1000,
                height = 800,
                rotation = ROTATION_180,
                sensorLocationY = 200,
                sensorWidth = 100,
            )

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation!!.left).isEqualTo(0)
            assertThat(sensorLocation!!.top).isEqualTo(500)
        }

    @Test
    fun horizontalSensorLocationIsAdjustedToScreenPositionForRotation0() =
        testScope.runTest {
            /*
            (0,0)   (500,0)   (600,0)   (1000,0)
               ____________===_________
              |                        |
              |  ^^^^^                 |
              |   status bar           |
              |                        |
               ------------------------ (1000, 800)
             */
            setupFPLocationAndDisplaySize(
                width = 1000,
                height = 800,
                rotation = ROTATION_0,
                sensorLocationX = 500,
                sensorWidth = 100,
            )

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation!!.left).isEqualTo(500)
            assertThat(sensorLocation!!.top).isEqualTo(0)
            assertThat(sensorLocation!!.isSensorVerticalInDefaultOrientation).isEqualTo(false)
            assertThat(sensorLocation!!.length).isEqualTo(100)
        }

    @Test
    fun horizontalSensorLocationIsAdjustedToScreenPositionForRotation90() =
        testScope.runTest {
            /*
                  (0,1000)   (0,500)   (0,400)   (0,0)
                        ____________===_________
                       |                        |
                       |               >        |
                       |   status bar  >        |
                       |               >        |
            (800, 1000) ------------------------
             */
            setupFPLocationAndDisplaySize(
                width = 800,
                height = 1000,
                rotation = ROTATION_90,
                sensorLocationX = 500,
                sensorWidth = 100,
            )

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation!!.left).isEqualTo(0)
            assertThat(sensorLocation!!.top).isEqualTo(400)
            assertThat(sensorLocation!!.isSensorVerticalInDefaultOrientation).isEqualTo(false)
            assertThat(sensorLocation!!.length).isEqualTo(100)
        }

    @Test
    fun horizontalSensorLocationIsAdjustedToScreenPositionForRotation180() =
        testScope.runTest {
            /*
            (1000, 800)  (500, 800)   (400, 800)   (0,800)
                       ____________===_________
                      |                        |
                      |                        |
                      |   status bar           |
                      |  \/ \/ \/ \/ \/ \/ \/  |
                       ------------------------ (0,0)
             */
            setupFPLocationAndDisplaySize(
                width = 1000,
                height = 800,
                rotation = ROTATION_180,
                sensorLocationX = 500,
                sensorWidth = 100,
            )

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation!!.left).isEqualTo(400)
            assertThat(sensorLocation!!.top).isEqualTo(800)
            assertThat(sensorLocation!!.isSensorVerticalInDefaultOrientation).isEqualTo(false)
            assertThat(sensorLocation!!.length).isEqualTo(100)
        }

    @Test
    fun horizontalSensorLocationIsAdjustedToScreenPositionForRotation270() =
        testScope.runTest {
            /*
                        (800, 500)  (800, 600)
            (800, 0) ____________===_________ (800,1000)
                    |  <                     |
                    |  <                     |
                    |  < status bar          |
                    |  <                     |
               (0,0) ------------------------
             */
            setupFPLocationAndDisplaySize(
                width = 800,
                height = 1000,
                rotation = ROTATION_270,
                sensorLocationX = 500,
                sensorWidth = 100,
            )

            val sensorLocation by collectLastValue(underTest.sensorLocation)
            assertThat(sensorLocation!!.left).isEqualTo(800)
            assertThat(sensorLocation!!.top).isEqualTo(500)
            assertThat(sensorLocation!!.isSensorVerticalInDefaultOrientation).isEqualTo(false)
            assertThat(sensorLocation!!.length).isEqualTo(100)
        }

    @Test
    fun isProlongedTouchRequiredForAuthentication_dependsOnSettingsToggle() =
        testScope.runTest {
            val isEnabled by collectLastValue(underTest.isProlongedTouchRequiredForAuthentication)
            setupFingerprint(FingerprintSensorType.POWER_BUTTON)

            isRestToUnlockEnabled.value = true
            runCurrent()
            assertThat(isEnabled).isTrue()

            isRestToUnlockEnabled.value = false
            runCurrent()
            assertThat(isEnabled).isFalse()
        }

    private suspend fun TestScope.setupFPLocationAndDisplaySize(
        width: Int,
        height: Int,
        sensorLocationX: Int = 0,
        sensorLocationY: Int = 0,
        rotation: DisplayRotation,
        sensorWidth: Int
    ) {
        setupDisplayDimensions(width, height)
        currentRotation.value = rotation
        setupFingerprint(
            x = sensorLocationX,
            y = sensorLocationY,
            displayId = "expanded_display",
            sensorRadius = sensorWidth / 2
        )
    }

    private fun setupDisplayDimensions(displayWidth: Int, displayHeight: Int) {
        whenever(windowManager.maximumWindowMetrics)
            .thenReturn(
                WindowMetrics(
                    Rect(0, 0, displayWidth, displayHeight),
                    mock(WindowInsets::class.java),
                )
            )
    }

    private suspend fun TestScope.setupFingerprint(
        fingerprintSensorType: FingerprintSensorType = FingerprintSensorType.POWER_BUTTON,
        x: Int = 0,
        y: Int = 0,
        displayId: String = "display_id_1",
        sensorRadius: Int = 150
    ) {
        contextDisplayInfo.uniqueId = displayId
        fingerprintRepository.setProperties(
            sensorId = 1,
            strength = SensorStrength.STRONG,
            sensorType = fingerprintSensorType,
            sensorLocations =
                mapOf(
                    "someOtherDisplayId" to
                        SensorLocationInternal(
                            "someOtherDisplayId",
                            x + 100,
                            y + 100,
                            sensorRadius,
                        ),
                    displayId to
                        SensorLocationInternal(
                            displayId,
                            x,
                            y,
                            sensorRadius,
                        )
                )
        )
        // Emit a display change event, this happens whenever any display related change happens,
        // rotation, active display changing etc, display switched off/on.
        displayChangeEvent.emit(1)

        runCurrent()
    }
}
