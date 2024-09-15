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

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.testKosmos
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verifyZeroInteractions
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class AuthDialogPanelInteractionDetectorTest(flags: FlagsParameterization?) : SysuiTestCase() {

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }

    @Mock private lateinit var action: Runnable

    lateinit var detector: AuthDialogPanelInteractionDetector

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        detector =
            AuthDialogPanelInteractionDetector(
                kosmos.applicationCoroutineScope,
                { kosmos.shadeInteractor },
            )
    }

    @Test
    fun enableDetector_expand_shouldRunAction() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeTestUtil.setShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN shade expands
            shadeTestUtil.setTracking(true)
            shadeTestUtil.setShadeExpansion(.5f)
            runCurrent()

            // THEN action was run
            verify(action).run()
        }

    @Test
    fun enableDetector_isUserInteractingTrue_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN isInteracting starts true
            shadeTestUtil.setTracking(true)
            runCurrent()
            detector.enable(action)

            // THEN action was not run
            verifyZeroInteractions(action)
        }

    @Test
    fun enableDetector_shadeExpandImmediate_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeTestUtil.setShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN shade expands fully instantly
            shadeTestUtil.setShadeExpansion(1f)
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)
            detector.disable()
        }

    @Test
    fun disableDetector_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeTestUtil.setShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN detector is disabled and shade opens
            detector.disable()
            runCurrent()
            shadeTestUtil.setTracking(true)
            shadeTestUtil.setShadeExpansion(.5f)
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)
        }

    @Test
    fun enableDetector_beginCollapse_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is open and detector is enabled
            shadeTestUtil.setShadeExpansion(1f)
            detector.enable(action)
            runCurrent()

            // WHEN shade begins to collapse
            shadeTestUtil.programmaticCollapseShade()
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)
            detector.disable()
        }
}
