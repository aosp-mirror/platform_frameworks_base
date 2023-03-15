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
 *
 */

package com.android.systemui.multishade.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.multishade.data.remoteproxy.MultiShadeInputProxy
import com.android.systemui.multishade.domain.interactor.MultiShadeInteractorTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class MultiShadeViewModelTest : SysuiTestCase() {

    private lateinit var testScope: TestScope
    private lateinit var inputProxy: MultiShadeInputProxy

    @Before
    fun setUp() {
        testScope = TestScope()
        inputProxy = MultiShadeInputProxy()
    }

    @Test
    fun scrim_whenDualShadeCollapsed() =
        testScope.runTest {
            val alpha = 0.5f
            overrideResource(R.dimen.dual_shade_scrim_alpha, alpha)
            overrideResource(R.bool.dual_shade_enabled, true)

            val underTest = create()
            val scrimAlpha: Float? by collectLastValue(underTest.scrimAlpha)
            val isScrimEnabled: Boolean? by collectLastValue(underTest.isScrimEnabled)

            assertThat(scrimAlpha).isZero()
            assertThat(isScrimEnabled).isFalse()
        }

    @Test
    fun scrim_whenDualShadeExpanded() =
        testScope.runTest {
            val alpha = 0.5f
            overrideResource(R.dimen.dual_shade_scrim_alpha, alpha)
            overrideResource(R.bool.dual_shade_enabled, true)
            val underTest = create()
            val scrimAlpha: Float? by collectLastValue(underTest.scrimAlpha)
            val isScrimEnabled: Boolean? by collectLastValue(underTest.isScrimEnabled)
            assertThat(scrimAlpha).isZero()
            assertThat(isScrimEnabled).isFalse()

            underTest.leftShade.onExpansionChanged(0.5f)
            assertThat(scrimAlpha).isEqualTo(alpha * 0.5f)
            assertThat(isScrimEnabled).isTrue()

            underTest.rightShade.onExpansionChanged(1f)
            assertThat(scrimAlpha).isEqualTo(alpha * 1f)
            assertThat(isScrimEnabled).isTrue()
        }

    @Test
    fun scrim_whenSingleShadeCollapsed() =
        testScope.runTest {
            val alpha = 0.5f
            overrideResource(R.dimen.dual_shade_scrim_alpha, alpha)
            overrideResource(R.bool.dual_shade_enabled, false)

            val underTest = create()
            val scrimAlpha: Float? by collectLastValue(underTest.scrimAlpha)
            val isScrimEnabled: Boolean? by collectLastValue(underTest.isScrimEnabled)

            assertThat(scrimAlpha).isZero()
            assertThat(isScrimEnabled).isFalse()
        }

    @Test
    fun scrim_whenSingleShadeExpanded() =
        testScope.runTest {
            val alpha = 0.5f
            overrideResource(R.dimen.dual_shade_scrim_alpha, alpha)
            overrideResource(R.bool.dual_shade_enabled, false)
            val underTest = create()
            val scrimAlpha: Float? by collectLastValue(underTest.scrimAlpha)
            val isScrimEnabled: Boolean? by collectLastValue(underTest.isScrimEnabled)

            underTest.singleShade.onExpansionChanged(0.95f)

            assertThat(scrimAlpha).isZero()
            assertThat(isScrimEnabled).isFalse()
        }

    private fun create(): MultiShadeViewModel {
        return MultiShadeViewModel(
            viewModelScope = testScope.backgroundScope,
            interactor =
                MultiShadeInteractorTest.create(
                    testScope = testScope,
                    context = context,
                    inputProxy = inputProxy,
                ),
        )
    }
}
