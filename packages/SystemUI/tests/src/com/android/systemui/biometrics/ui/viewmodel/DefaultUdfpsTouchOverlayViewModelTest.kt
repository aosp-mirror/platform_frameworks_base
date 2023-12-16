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

package com.android.systemui.biometrics.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.TestMocksModule
import com.android.systemui.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class DefaultUdfpsTouchOverlayViewModelTest : SysuiTestCase() {
    @Captor
    private lateinit var sysuiDialogListenerCaptor: ArgumentCaptor<SystemUIDialogManager.Listener>
    private var systemUIDialogManager: SystemUIDialogManager = mock()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<DefaultUdfpsTouchOverlayViewModel> {
        val keyguardRepository: FakeKeyguardRepository
        val shadeRepository: FakeShadeRepository
        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }

    private fun TestComponent.shadeExpanded(expanded: Boolean) {
        if (expanded) {
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            shadeRepository.setLegacyExpandedOrAwaitingInputTransfer(true)
        } else {
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            shadeRepository.setLegacyExpandedOrAwaitingInputTransfer(false)
        }
    }

    private val testComponent: TestComponent =
        DaggerDefaultUdfpsTouchOverlayViewModelTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule { set(Flags.FULL_SCREEN_USER_SWITCHER, true) },
                mocks = TestMocksModule(systemUIDialogManager = systemUIDialogManager),
            )

    @Test
    fun shadeNotExpanded_noDialogShowing_shouldHandleTouchesTrue() =
        testComponent.runTest {
            val shouldHandleTouches by collectLastValue(underTest.shouldHandleTouches)
            runCurrent()

            shadeExpanded(false)
            verify(systemUIDialogManager).registerListener(sysuiDialogListenerCaptor.capture())
            sysuiDialogListenerCaptor.value.shouldHideAffordances(false)
            runCurrent()

            assertThat(shouldHandleTouches).isTrue()
        }

    @Test
    fun shadeNotExpanded_dialogShowing_shouldHandleTouchesFalse() =
        testComponent.runTest {
            val shouldHandleTouches by collectLastValue(underTest.shouldHandleTouches)
            runCurrent()

            shadeExpanded(false)
            verify(systemUIDialogManager).registerListener(sysuiDialogListenerCaptor.capture())
            sysuiDialogListenerCaptor.value.shouldHideAffordances(true)
            runCurrent()

            assertThat(shouldHandleTouches).isFalse()
        }

    @Test
    fun shadeExpanded_noDialogShowing_shouldHandleTouchesFalse() =
        testComponent.runTest {
            val shouldHandleTouches by collectLastValue(underTest.shouldHandleTouches)
            runCurrent()

            shadeExpanded(true)
            verify(systemUIDialogManager).registerListener(sysuiDialogListenerCaptor.capture())
            sysuiDialogListenerCaptor.value.shouldHideAffordances(false)
            runCurrent()

            assertThat(shouldHandleTouches).isFalse()
        }
}
