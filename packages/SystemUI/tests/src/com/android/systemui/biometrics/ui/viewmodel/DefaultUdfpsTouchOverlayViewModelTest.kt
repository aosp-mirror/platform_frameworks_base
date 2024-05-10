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

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.systemUIDialogManager
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class DefaultUdfpsTouchOverlayViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, true) }
        }
    private val testScope = kosmos.testScope

    @Captor
    private lateinit var sysuiDialogListenerCaptor: ArgumentCaptor<SystemUIDialogManager.Listener>
    private var systemUIDialogManager = kosmos.systemUIDialogManager
    private val keyguardRepository = kosmos.fakeKeyguardRepository

    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }

    private lateinit var underTest: DefaultUdfpsTouchOverlayViewModel

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return parameterizeSceneContainerFlag()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            DefaultUdfpsTouchOverlayViewModel(
                kosmos.shadeInteractor,
                systemUIDialogManager,
            )
    }

    private fun shadeExpanded(expanded: Boolean) {
        if (expanded) {
            shadeTestUtil.setShadeExpansion(1f)
            shadeTestUtil.setTracking(false)
            shadeTestUtil.setLegacyExpandedOrAwaitingInputTransfer(true)
        } else {
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeTestUtil.setShadeExpansion(0f)
            shadeTestUtil.setTracking(false)
            shadeTestUtil.setLegacyExpandedOrAwaitingInputTransfer(false)
        }
    }

    @Test
    @BrokenWithSceneContainer(339465026)
    fun shadeNotExpanded_noDialogShowing_shouldHandleTouchesTrue() =
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
            val shouldHandleTouches by collectLastValue(underTest.shouldHandleTouches)
            runCurrent()

            shadeExpanded(true)
            verify(systemUIDialogManager).registerListener(sysuiDialogListenerCaptor.capture())
            sysuiDialogListenerCaptor.value.shouldHideAffordances(false)
            runCurrent()

            assertThat(shouldHandleTouches).isFalse()
        }
}
