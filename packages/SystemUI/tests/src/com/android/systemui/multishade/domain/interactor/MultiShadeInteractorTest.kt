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

package com.android.systemui.multishade.domain.interactor

import android.content.Context
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.multishade.data.remoteproxy.MultiShadeInputProxy
import com.android.systemui.multishade.data.repository.MultiShadeRepositoryTest
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
class MultiShadeInteractorTest : SysuiTestCase() {

    private lateinit var testScope: TestScope
    private lateinit var inputProxy: MultiShadeInputProxy

    @Before
    fun setUp() {
        testScope = TestScope()
        inputProxy = MultiShadeInputProxy()
    }

    @Test
    fun maxShadeExpansion() =
        testScope.runTest {
            val underTest = create()
            val maxShadeExpansion: Float? by collectLastValue(underTest.maxShadeExpansion)
            assertWithMessage("maxShadeExpansion must start with 0.0!")
                .that(maxShadeExpansion)
                .isEqualTo(0f)

            underTest.setExpansion(shadeId = ShadeId.LEFT, expansion = 0.441f)
            assertThat(maxShadeExpansion).isEqualTo(0.441f)

            underTest.setExpansion(shadeId = ShadeId.RIGHT, expansion = 0.442f)
            assertThat(maxShadeExpansion).isEqualTo(0.442f)

            underTest.setExpansion(shadeId = ShadeId.RIGHT, expansion = 0f)
            assertThat(maxShadeExpansion).isEqualTo(0.441f)

            underTest.setExpansion(shadeId = ShadeId.LEFT, expansion = 0f)
            assertThat(maxShadeExpansion).isEqualTo(0f)
        }

    @Test
    fun isAnyShadeExpanded() =
        testScope.runTest {
            val underTest = create()
            val isAnyShadeExpanded: Boolean? by collectLastValue(underTest.isAnyShadeExpanded)
            assertWithMessage("isAnyShadeExpanded must start with false!")
                .that(isAnyShadeExpanded)
                .isFalse()

            underTest.setExpansion(shadeId = ShadeId.LEFT, expansion = 0.441f)
            assertThat(isAnyShadeExpanded).isTrue()

            underTest.setExpansion(shadeId = ShadeId.RIGHT, expansion = 0.442f)
            assertThat(isAnyShadeExpanded).isTrue()

            underTest.setExpansion(shadeId = ShadeId.RIGHT, expansion = 0f)
            assertThat(isAnyShadeExpanded).isTrue()

            underTest.setExpansion(shadeId = ShadeId.LEFT, expansion = 0f)
            assertThat(isAnyShadeExpanded).isFalse()
        }

    @Test
    fun isVisible_dualShadeConfig() =
        testScope.runTest {
            overrideResource(R.bool.dual_shade_enabled, true)
            val underTest = create()
            val isLeftShadeVisible: Boolean? by collectLastValue(underTest.isVisible(ShadeId.LEFT))
            val isRightShadeVisible: Boolean? by
                collectLastValue(underTest.isVisible(ShadeId.RIGHT))
            val isSingleShadeVisible: Boolean? by
                collectLastValue(underTest.isVisible(ShadeId.SINGLE))

            assertThat(isLeftShadeVisible).isTrue()
            assertThat(isRightShadeVisible).isTrue()
            assertThat(isSingleShadeVisible).isFalse()
        }

    @Test
    fun isVisible_singleShadeConfig() =
        testScope.runTest {
            overrideResource(R.bool.dual_shade_enabled, false)
            val underTest = create()
            val isLeftShadeVisible: Boolean? by collectLastValue(underTest.isVisible(ShadeId.LEFT))
            val isRightShadeVisible: Boolean? by
                collectLastValue(underTest.isVisible(ShadeId.RIGHT))
            val isSingleShadeVisible: Boolean? by
                collectLastValue(underTest.isVisible(ShadeId.SINGLE))

            assertThat(isLeftShadeVisible).isFalse()
            assertThat(isRightShadeVisible).isFalse()
            assertThat(isSingleShadeVisible).isTrue()
        }

    @Test
    fun isNonProxiedInputAllowed() =
        testScope.runTest {
            val underTest = create()
            val isLeftShadeNonProxiedInputAllowed: Boolean? by
                collectLastValue(underTest.isNonProxiedInputAllowed(ShadeId.LEFT))
            assertWithMessage("isNonProxiedInputAllowed should start as true!")
                .that(isLeftShadeNonProxiedInputAllowed)
                .isTrue()

            // Need to collect proxied input so the flows become hot as the gesture cancelation code
            // logic sits in side the proxiedInput flow for each shade.
            collectLastValue(underTest.proxiedInput(ShadeId.LEFT))
            collectLastValue(underTest.proxiedInput(ShadeId.RIGHT))

            // Starting a proxied interaction on the LEFT shade disallows non-proxied interaction on
            // the
            // same shade.
            inputProxy.onProxiedInput(
                ProxiedInputModel.OnDrag(xFraction = 0f, yDragAmountPx = 123f)
            )
            assertThat(isLeftShadeNonProxiedInputAllowed).isFalse()

            // Registering the end of the proxied interaction re-allows it.
            inputProxy.onProxiedInput(ProxiedInputModel.OnDragEnd)
            assertThat(isLeftShadeNonProxiedInputAllowed).isTrue()

            // Starting a proxied interaction on the RIGHT shade force-collapses the LEFT shade,
            // disallowing non-proxied input on the LEFT shade.
            inputProxy.onProxiedInput(
                ProxiedInputModel.OnDrag(xFraction = 1f, yDragAmountPx = 123f)
            )
            assertThat(isLeftShadeNonProxiedInputAllowed).isFalse()

            // Registering the end of the interaction on the RIGHT shade re-allows it.
            inputProxy.onProxiedInput(ProxiedInputModel.OnDragEnd)
            assertThat(isLeftShadeNonProxiedInputAllowed).isTrue()
        }

    @Test
    fun isForceCollapsed_whenOtherShadeInteractionUnderway() =
        testScope.runTest {
            val underTest = create()
            val isLeftShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.LEFT))
            val isRightShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.RIGHT))
            val isSingleShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.SINGLE))

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
            underTest.onUserInteractionStarted(ShadeId.RIGHT)
            assertThat(isLeftShadeForceCollapsed).isTrue()
            assertThat(isRightShadeForceCollapsed).isFalse()
            assertThat(isSingleShadeForceCollapsed).isFalse()

            // Registering the end of the interaction on the RIGHT shade re-allows it.
            underTest.onUserInteractionEnded(ShadeId.RIGHT)
            assertThat(isLeftShadeForceCollapsed).isFalse()
            assertThat(isRightShadeForceCollapsed).isFalse()
            assertThat(isSingleShadeForceCollapsed).isFalse()

            // Registering the start of an interaction on the LEFT shade force-collapses the RIGHT
            // shade.
            underTest.onUserInteractionStarted(ShadeId.LEFT)
            assertThat(isLeftShadeForceCollapsed).isFalse()
            assertThat(isRightShadeForceCollapsed).isTrue()
            assertThat(isSingleShadeForceCollapsed).isFalse()

            // Registering the end of the interaction on the LEFT shade re-allows it.
            underTest.onUserInteractionEnded(ShadeId.LEFT)
            assertThat(isLeftShadeForceCollapsed).isFalse()
            assertThat(isRightShadeForceCollapsed).isFalse()
            assertThat(isSingleShadeForceCollapsed).isFalse()
        }

    @Test
    fun collapseAll() =
        testScope.runTest {
            val underTest = create()
            val isLeftShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.LEFT))
            val isRightShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.RIGHT))
            val isSingleShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.SINGLE))

            assertWithMessage("isForceCollapsed should start as false!")
                .that(isLeftShadeForceCollapsed)
                .isFalse()
            assertWithMessage("isForceCollapsed should start as false!")
                .that(isRightShadeForceCollapsed)
                .isFalse()
            assertWithMessage("isForceCollapsed should start as false!")
                .that(isSingleShadeForceCollapsed)
                .isFalse()

            underTest.collapseAll()
            assertThat(isLeftShadeForceCollapsed).isTrue()
            assertThat(isRightShadeForceCollapsed).isTrue()
            assertThat(isSingleShadeForceCollapsed).isTrue()

            // Receiving proxied input on that's not a tap gesture, on the left-hand side resets the
            // "collapse all". Note that now the RIGHT shade is force-collapsed because we're
            // interacting with the LEFT shade.
            inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0f, 0f))
            assertThat(isLeftShadeForceCollapsed).isFalse()
            assertThat(isRightShadeForceCollapsed).isTrue()
            assertThat(isSingleShadeForceCollapsed).isFalse()
        }

    @Test
    fun onTapOutside_collapsesAll() =
        testScope.runTest {
            val underTest = create()
            val isLeftShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.LEFT))
            val isRightShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.RIGHT))
            val isSingleShadeForceCollapsed: Boolean? by
                collectLastValue(underTest.isForceCollapsed(ShadeId.SINGLE))

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
            val underTest = create()
            val proxiedInput: ProxiedInputModel? by
                collectLastValue(underTest.proxiedInput(ShadeId.RIGHT))
            underTest.onUserInteractionStarted(shadeId = ShadeId.RIGHT)

            inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0.9f, 100f))
            assertThat(proxiedInput).isNull()

            inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0.8f, 110f))
            assertThat(proxiedInput).isNull()

            underTest.onUserInteractionEnded(shadeId = ShadeId.RIGHT)

            inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0.9f, 100f))
            assertThat(proxiedInput).isNotNull()
        }

    private fun create(): MultiShadeInteractor {
        return create(
            testScope = testScope,
            context = context,
            inputProxy = inputProxy,
        )
    }

    companion object {
        fun create(
            testScope: TestScope,
            context: Context,
            inputProxy: MultiShadeInputProxy,
        ): MultiShadeInteractor {
            return MultiShadeInteractor(
                applicationScope = testScope.backgroundScope,
                repository =
                    MultiShadeRepositoryTest.create(
                        context = context,
                        inputProxy = inputProxy,
                    ),
                inputProxy = inputProxy,
            )
        }
    }
}
