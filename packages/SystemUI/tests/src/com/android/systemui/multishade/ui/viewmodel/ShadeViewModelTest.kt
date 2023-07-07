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
import com.android.systemui.multishade.domain.interactor.MultiShadeInteractor
import com.android.systemui.multishade.domain.interactor.MultiShadeInteractorTest
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.shared.model.ShadeId
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
class ShadeViewModelTest : SysuiTestCase() {

    private lateinit var testScope: TestScope
    private lateinit var inputProxy: MultiShadeInputProxy
    private var interactor: MultiShadeInteractor? = null

    @Before
    fun setUp() {
        testScope = TestScope()
        inputProxy = MultiShadeInputProxy()
    }

    @Test
    fun isVisible_dualShadeConfig() =
        testScope.runTest {
            overrideResource(R.bool.dual_shade_enabled, true)
            val isLeftShadeVisible: Boolean? by collectLastValue(create(ShadeId.LEFT).isVisible)
            val isRightShadeVisible: Boolean? by collectLastValue(create(ShadeId.RIGHT).isVisible)
            val isSingleShadeVisible: Boolean? by collectLastValue(create(ShadeId.SINGLE).isVisible)

            assertThat(isLeftShadeVisible).isTrue()
            assertThat(isRightShadeVisible).isTrue()
            assertThat(isSingleShadeVisible).isFalse()
        }

    @Test
    fun isVisible_singleShadeConfig() =
        testScope.runTest {
            overrideResource(R.bool.dual_shade_enabled, false)
            val isLeftShadeVisible: Boolean? by collectLastValue(create(ShadeId.LEFT).isVisible)
            val isRightShadeVisible: Boolean? by collectLastValue(create(ShadeId.RIGHT).isVisible)
            val isSingleShadeVisible: Boolean? by collectLastValue(create(ShadeId.SINGLE).isVisible)

            assertThat(isLeftShadeVisible).isFalse()
            assertThat(isRightShadeVisible).isFalse()
            assertThat(isSingleShadeVisible).isTrue()
        }

    @Test
    fun isSwipingEnabled() =
        testScope.runTest {
            val underTest = create(ShadeId.LEFT)
            val isSwipingEnabled: Boolean? by collectLastValue(underTest.isSwipingEnabled)
            assertWithMessage("isSwipingEnabled should start as true!")
                .that(isSwipingEnabled)
                .isTrue()

            // Need to collect proxied input so the flows become hot as the gesture cancelation code
            // logic sits in side the proxiedInput flow for each shade.
            collectLastValue(underTest.proxiedInput)
            collectLastValue(create(ShadeId.RIGHT).proxiedInput)

            // Starting a proxied interaction on the LEFT shade disallows non-proxied interaction on
            // the
            // same shade.
            inputProxy.onProxiedInput(
                ProxiedInputModel.OnDrag(xFraction = 0f, yDragAmountPx = 123f)
            )
            assertThat(isSwipingEnabled).isFalse()

            // Registering the end of the proxied interaction re-allows it.
            inputProxy.onProxiedInput(ProxiedInputModel.OnDragEnd)
            assertThat(isSwipingEnabled).isTrue()

            // Starting a proxied interaction on the RIGHT shade force-collapses the LEFT shade,
            // disallowing non-proxied input on the LEFT shade.
            inputProxy.onProxiedInput(
                ProxiedInputModel.OnDrag(xFraction = 1f, yDragAmountPx = 123f)
            )
            assertThat(isSwipingEnabled).isFalse()

            // Registering the end of the interaction on the RIGHT shade re-allows it.
            inputProxy.onProxiedInput(ProxiedInputModel.OnDragEnd)
            assertThat(isSwipingEnabled).isTrue()
        }

    @Test
    fun isForceCollapsed_whenOtherShadeInteractionUnderway() =
        testScope.runTest {
            val leftShade = create(ShadeId.LEFT)
            val rightShade = create(ShadeId.RIGHT)
            val isLeftShadeForceCollapsed: Boolean? by collectLastValue(leftShade.isForceCollapsed)
            val isRightShadeForceCollapsed: Boolean? by
                collectLastValue(rightShade.isForceCollapsed)
            val isSingleShadeForceCollapsed: Boolean? by
                collectLastValue(create(ShadeId.SINGLE).isForceCollapsed)

            assertWithMessage("isForceCollapsed should start as false!")
                .that(isLeftShadeForceCollapsed)
                .isFalse()
            assertWithMessage("isForceCollapsed should start as false!")
                .that(isRightShadeForceCollapsed)
                .isFalse()
            assertWithMessage("isForceCollapsed should start as false!")
                .that(isSingleShadeForceCollapsed)
                .isFalse()

            // Registering the start of an interaction on the RIGHT shade force-collapses the LEFT
            // shade.
            rightShade.onDragStarted()
            assertThat(isLeftShadeForceCollapsed).isTrue()
            assertThat(isRightShadeForceCollapsed).isFalse()
            assertThat(isSingleShadeForceCollapsed).isFalse()

            // Registering the end of the interaction on the RIGHT shade re-allows it.
            rightShade.onDragEnded()
            assertThat(isLeftShadeForceCollapsed).isFalse()
            assertThat(isRightShadeForceCollapsed).isFalse()
            assertThat(isSingleShadeForceCollapsed).isFalse()

            // Registering the start of an interaction on the LEFT shade force-collapses the RIGHT
            // shade.
            leftShade.onDragStarted()
            assertThat(isLeftShadeForceCollapsed).isFalse()
            assertThat(isRightShadeForceCollapsed).isTrue()
            assertThat(isSingleShadeForceCollapsed).isFalse()

            // Registering the end of the interaction on the LEFT shade re-allows it.
            leftShade.onDragEnded()
            assertThat(isLeftShadeForceCollapsed).isFalse()
            assertThat(isRightShadeForceCollapsed).isFalse()
            assertThat(isSingleShadeForceCollapsed).isFalse()
        }

    @Test
    fun onTapOutside_collapsesAll() =
        testScope.runTest {
            val isLeftShadeForceCollapsed: Boolean? by
                collectLastValue(create(ShadeId.LEFT).isForceCollapsed)
            val isRightShadeForceCollapsed: Boolean? by
                collectLastValue(create(ShadeId.RIGHT).isForceCollapsed)
            val isSingleShadeForceCollapsed: Boolean? by
                collectLastValue(create(ShadeId.SINGLE).isForceCollapsed)

            assertWithMessage("isForceCollapsed should start as false!")
                .that(isLeftShadeForceCollapsed)
                .isFalse()
            assertWithMessage("isForceCollapsed should start as false!")
                .that(isRightShadeForceCollapsed)
                .isFalse()
            assertWithMessage("isForceCollapsed should start as false!")
                .that(isSingleShadeForceCollapsed)
                .isFalse()

            inputProxy.onProxiedInput(ProxiedInputModel.OnTap)
            assertThat(isLeftShadeForceCollapsed).isTrue()
            assertThat(isRightShadeForceCollapsed).isTrue()
            assertThat(isSingleShadeForceCollapsed).isTrue()
        }

    @Test
    fun proxiedInput_ignoredWhileNonProxiedGestureUnderway() =
        testScope.runTest {
            val underTest = create(ShadeId.RIGHT)
            val proxiedInput: ProxiedInputModel? by collectLastValue(underTest.proxiedInput)
            underTest.onDragStarted()

            inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0.9f, 100f))
            assertThat(proxiedInput).isNull()

            inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0.8f, 110f))
            assertThat(proxiedInput).isNull()

            underTest.onDragEnded()

            inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0.9f, 100f))
            assertThat(proxiedInput).isNotNull()
        }

    private fun create(
        shadeId: ShadeId,
    ): ShadeViewModel {
        return ShadeViewModel(
            viewModelScope = testScope.backgroundScope,
            shadeId = shadeId,
            interactor = interactor
                    ?: MultiShadeInteractorTest.create(
                            testScope = testScope,
                            context = context,
                            inputProxy = inputProxy,
                        )
                        .also { interactor = it },
        )
    }
}
