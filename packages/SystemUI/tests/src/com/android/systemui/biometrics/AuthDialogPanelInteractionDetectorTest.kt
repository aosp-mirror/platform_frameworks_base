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

import androidx.test.filters.SmallTest
import com.android.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class AuthDialogPanelInteractionDetectorTest : SysuiTestCase() {

    private val testComponent: TestComponent =
        DaggerAuthDialogPanelInteractionDetectorTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        set(Flags.FACE_AUTH_REFACTOR, false)
                        set(Flags.FULL_SCREEN_USER_SWITCHER, true)
                    },
            )

    @Mock private lateinit var action: Runnable

    private val testScope = testComponent.testScope
    private val shadeRepository = testComponent.shadeRepository
    private val detector = testComponent.detector

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun enableDetector_expand_shouldRunAction() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeRepository.setLegacyShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN shade expands
            shadeRepository.setLegacyShadeTracking(true)
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN action was run
            verify(action).run()
        }

    @Test
    fun enableDetector_shadeExpandImmediate_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeRepository.setLegacyShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN shade expands fully instantly
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)

            // Clean up job
            detector.disable()
        }

    @Test
    fun disableDetector_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is closed and detector is enabled
            shadeRepository.setLegacyShadeExpansion(0f)
            detector.enable(action)
            runCurrent()

            // WHEN detector is disabled and shade opens
            detector.disable()
            shadeRepository.setLegacyShadeTracking(true)
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)
        }

    @Test
    fun enableDetector_beginCollapse_shouldNotPostRunnable() =
        testScope.runTest {
            // GIVEN shade is open and detector is enabled
            shadeRepository.setLegacyShadeExpansion(1f)
            detector.enable(action)
            runCurrent()

            // WHEN shade begins to collapse
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN action not run
            verifyZeroInteractions(action)

            // Clean up job
            detector.disable()
        }

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent {

        val detector: AuthDialogPanelInteractionDetector
        val shadeRepository: FakeShadeRepository
        val testScope: TestScope

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
            ): TestComponent
        }
    }
}
