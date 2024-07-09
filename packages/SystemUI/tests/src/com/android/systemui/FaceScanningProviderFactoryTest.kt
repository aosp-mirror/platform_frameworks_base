/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui

import android.graphics.Point
import android.hardware.display.DisplayManagerGlobal
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import android.view.DisplayAdjustments
import android.view.DisplayInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.data.repository.FakeFacePropertyRepository
import com.android.systemui.decor.FaceScanningProviderFactory
import com.android.systemui.log.ScreenDecorationsLogger
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@RunWithLooper
@RunWith(AndroidJUnit4::class)
@SmallTest
class FaceScanningProviderFactoryTest : SysuiTestCase() {

    private lateinit var underTest: FaceScanningProviderFactory

    @Mock private lateinit var authController: AuthController

    @Mock private lateinit var statusBarStateController: StatusBarStateController

    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    private val facePropertyRepository = FakeFacePropertyRepository()

    private val displayId = 2

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        val displayInfo = DisplayInfo()
        val dmGlobal = mock(DisplayManagerGlobal::class.java)
        val display =
            Display(
                dmGlobal,
                displayId,
                displayInfo,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS
            )
        whenever(dmGlobal.getDisplayInfo(eq(displayId))).thenReturn(displayInfo)
        val displayContext = context.createDisplayContext(display) as SysuiTestableContext
        displayContext.orCreateTestableResources.addOverride(
            R.array.config_displayUniqueIdArray,
            arrayOf(displayId)
        )
        displayContext.orCreateTestableResources.addOverride(
            R.bool.config_fillMainBuiltInDisplayCutout,
            true
        )
        underTest =
            FaceScanningProviderFactory(
                authController,
                displayContext,
                statusBarStateController,
                keyguardUpdateMonitor,
                mock(Executor::class.java),
                ScreenDecorationsLogger(logcatLogBuffer("FaceScanningProviderFactoryTest")),
                facePropertyRepository,
            )

        facePropertyRepository.setSensorLocation(Point(10, 10))
    }

    @Test
    fun shouldNotShowFaceScanningAnimationIfFaceIsNotEnrolled() {
        whenever(keyguardUpdateMonitor.isFaceEnabledAndEnrolled).thenReturn(false)
        whenever(authController.isShowing).thenReturn(true)

        assertThat(underTest.shouldShowFaceScanningAnim()).isFalse()
    }

    @Test
    fun shouldShowFaceScanningAnimationIfBiometricPromptIsShowing() {
        whenever(keyguardUpdateMonitor.isFaceEnabledAndEnrolled).thenReturn(true)
        whenever(authController.isShowing).thenReturn(true)

        assertThat(underTest.shouldShowFaceScanningAnim()).isTrue()
    }

    @Test
    fun shouldShowFaceScanningAnimationIfKeyguardFaceDetectionIsShowing() {
        whenever(keyguardUpdateMonitor.isFaceEnabledAndEnrolled).thenReturn(true)
        whenever(keyguardUpdateMonitor.isFaceDetectionRunning).thenReturn(true)

        assertThat(underTest.shouldShowFaceScanningAnim()).isTrue()
    }
}
