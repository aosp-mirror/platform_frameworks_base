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
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.view.MotionEvent
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.authController
import com.android.systemui.biometrics.fingerprintManager
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class UdfpsOverlayInteractorTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private val kosmos = testKosmos()

    private val testScope: TestScope = kosmos.testScope

    private val authController: AuthController = kosmos.authController
    private val fingerprintManager: FingerprintManager = kosmos.fingerprintManager
    @Mock private lateinit var fingerprintSensorProperties: FingerprintSensorPropertiesInternal
    @Captor private lateinit var authControllerCallback: ArgumentCaptor<AuthController.Callback>

    @Mock private lateinit var udfpsOverlayParams: UdfpsOverlayParams
    @Mock private lateinit var overlayBounds: Rect

    private lateinit var underTest: UdfpsOverlayInteractor

    @Test
    fun testShouldInterceptTouch() =
        testScope.runTest {
            createUdfpsOverlayInteractor()

            // When fingerprint enrolled and touch is within bounds
            verify(authController).addCallback(authControllerCallback.capture())
            authControllerCallback.value.onUdfpsLocationChanged(udfpsOverlayParams)
            whenever(authController.isUdfpsEnrolled(anyInt())).thenReturn(true)
            whenever(udfpsOverlayParams.overlayBounds).thenReturn(overlayBounds)
            whenever(overlayBounds.contains(downEv.x.toInt(), downEv.y.toInt())).thenReturn(true)

            runCurrent()

            // Then touch is within udfps area
            assertThat(underTest.isTouchWithinUdfpsArea(downEv)).isTrue()

            // When touch is outside of bounds
            whenever(overlayBounds.contains(downEv.x.toInt(), downEv.y.toInt())).thenReturn(false)

            // Then touch is not within udfps area
            assertThat(underTest.isTouchWithinUdfpsArea(downEv)).isFalse()
        }

    @Test
    fun testUdfpsOverlayParamsChange() =
        testScope.runTest {
            createUdfpsOverlayInteractor()
            val udfpsOverlayParams = collectLastValue(underTest.udfpsOverlayParams)
            runCurrent()

            verify(authController).addCallback(authControllerCallback.capture())

            // When udfpsLocationChanges in authcontroller
            authControllerCallback.value.onUdfpsLocationChanged(firstParams)

            // Then the value in the interactor should be updated
            assertThat(udfpsOverlayParams()).isEqualTo(firstParams)
        }

    @Test
    fun testPaddingIsNeverNegative() =
        testScope.runTest {
            context.orCreateTestableResources.addOverride(R.dimen.pixel_pitch, 60.0f)
            createUdfpsOverlayInteractor()
            val padding by collectLastValue(underTest.iconPadding)
            runCurrent()

            verify(authController).addCallback(authControllerCallback.capture())

            // Simulate the first empty udfps overlay params value.
            authControllerCallback.value.onUdfpsLocationChanged(UdfpsOverlayParams())
            runCurrent()

            assertThat(padding).isEqualTo(0)
            context.orCreateTestableResources.removeOverride(R.dimen.pixel_pitch)
        }

    @Test
    fun testSetIgnoreDisplayTouches() =
        testScope.runTest {
            createUdfpsOverlayInteractor()
            whenever(authController.isUdfpsSupported).thenReturn(true)
            whenever(authController.udfpsProps).thenReturn(listOf(fingerprintSensorProperties))

            underTest.setHandleTouches(false)
            verify(fingerprintManager).setIgnoreDisplayTouches(anyLong(), anyInt(), eq(true))

            underTest.setHandleTouches(true)
            verify(fingerprintManager).setIgnoreDisplayTouches(anyLong(), anyInt(), eq(false))
        }

    private fun createUdfpsOverlayInteractor() {
        underTest = kosmos.udfpsOverlayInteractor
        testScope.runCurrent()
    }
}

private val firstParams =
    UdfpsOverlayParams(Rect(0, 0, 10, 10), Rect(0, 0, 10, 10), 1, 1, 1f, Surface.ROTATION_0)
private val downEv = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
