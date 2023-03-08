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

package com.android.systemui.multishade.data.repository

import android.content.Context
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.multishade.data.model.MultiShadeInteractionModel
import com.android.systemui.multishade.data.remoteproxy.MultiShadeInputProxy
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.shared.model.ShadeConfig
import com.android.systemui.multishade.shared.model.ShadeId
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class MultiShadeRepositoryTest : SysuiTestCase() {

    private lateinit var inputProxy: MultiShadeInputProxy

    @Before
    fun setUp() {
        inputProxy = MultiShadeInputProxy()
    }

    @Test
    fun proxiedInput() = runTest {
        val underTest = create()
        val latest: ProxiedInputModel? by collectLastValue(underTest.proxiedInput)

        assertWithMessage("proxiedInput should start with null").that(latest).isNull()

        inputProxy.onProxiedInput(ProxiedInputModel.OnTap)
        assertThat(latest).isEqualTo(ProxiedInputModel.OnTap)

        inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0f, 100f))
        assertThat(latest).isEqualTo(ProxiedInputModel.OnDrag(0f, 100f))

        inputProxy.onProxiedInput(ProxiedInputModel.OnDrag(0f, 120f))
        assertThat(latest).isEqualTo(ProxiedInputModel.OnDrag(0f, 120f))

        inputProxy.onProxiedInput(ProxiedInputModel.OnDragEnd)
        assertThat(latest).isEqualTo(ProxiedInputModel.OnDragEnd)
    }

    @Test
    fun shadeConfig_dualShadeEnabled() = runTest {
        overrideResource(R.bool.dual_shade_enabled, true)
        val underTest = create()
        val shadeConfig: ShadeConfig? by collectLastValue(underTest.shadeConfig)

        assertThat(shadeConfig).isInstanceOf(ShadeConfig.DualShadeConfig::class.java)
    }

    @Test
    fun shadeConfig_dualShadeNotEnabled() = runTest {
        overrideResource(R.bool.dual_shade_enabled, false)
        val underTest = create()
        val shadeConfig: ShadeConfig? by collectLastValue(underTest.shadeConfig)

        assertThat(shadeConfig).isInstanceOf(ShadeConfig.SingleShadeConfig::class.java)
    }

    @Test
    fun forceCollapseAll() = runTest {
        val underTest = create()
        val forceCollapseAll: Boolean? by collectLastValue(underTest.forceCollapseAll)

        assertWithMessage("forceCollapseAll should start as false!")
            .that(forceCollapseAll)
            .isFalse()

        underTest.setForceCollapseAll(true)
        assertThat(forceCollapseAll).isTrue()

        underTest.setForceCollapseAll(false)
        assertThat(forceCollapseAll).isFalse()
    }

    @Test
    fun shadeInteraction() = runTest {
        val underTest = create()
        val shadeInteraction: MultiShadeInteractionModel? by
            collectLastValue(underTest.shadeInteraction)

        assertWithMessage("shadeInteraction should start as null!").that(shadeInteraction).isNull()

        underTest.setShadeInteraction(
            MultiShadeInteractionModel(shadeId = ShadeId.LEFT, isProxied = false)
        )
        assertThat(shadeInteraction)
            .isEqualTo(MultiShadeInteractionModel(shadeId = ShadeId.LEFT, isProxied = false))

        underTest.setShadeInteraction(
            MultiShadeInteractionModel(shadeId = ShadeId.RIGHT, isProxied = true)
        )
        assertThat(shadeInteraction)
            .isEqualTo(MultiShadeInteractionModel(shadeId = ShadeId.RIGHT, isProxied = true))

        underTest.setShadeInteraction(null)
        assertThat(shadeInteraction).isNull()
    }

    @Test
    fun expansion() = runTest {
        val underTest = create()
        val leftExpansion: Float? by
            collectLastValue(underTest.getShade(ShadeId.LEFT).map { it.expansion })
        val rightExpansion: Float? by
            collectLastValue(underTest.getShade(ShadeId.RIGHT).map { it.expansion })
        val singleExpansion: Float? by
            collectLastValue(underTest.getShade(ShadeId.SINGLE).map { it.expansion })

        assertWithMessage("expansion should start as 0!").that(leftExpansion).isZero()
        assertWithMessage("expansion should start as 0!").that(rightExpansion).isZero()
        assertWithMessage("expansion should start as 0!").that(singleExpansion).isZero()

        underTest.setExpansion(
            shadeId = ShadeId.LEFT,
            0.4f,
        )
        assertThat(leftExpansion).isEqualTo(0.4f)
        assertThat(rightExpansion).isEqualTo(0f)
        assertThat(singleExpansion).isEqualTo(0f)

        underTest.setExpansion(
            shadeId = ShadeId.RIGHT,
            0.73f,
        )
        assertThat(leftExpansion).isEqualTo(0.4f)
        assertThat(rightExpansion).isEqualTo(0.73f)
        assertThat(singleExpansion).isEqualTo(0f)

        underTest.setExpansion(
            shadeId = ShadeId.LEFT,
            0.1f,
        )
        underTest.setExpansion(
            shadeId = ShadeId.SINGLE,
            0.88f,
        )
        assertThat(leftExpansion).isEqualTo(0.1f)
        assertThat(rightExpansion).isEqualTo(0.73f)
        assertThat(singleExpansion).isEqualTo(0.88f)
    }

    private fun create(): MultiShadeRepository {
        return create(
            context = context,
            inputProxy = inputProxy,
        )
    }

    companion object {
        fun create(
            context: Context,
            inputProxy: MultiShadeInputProxy,
        ): MultiShadeRepository {
            return MultiShadeRepository(
                applicationContext = context,
                inputProxy = inputProxy,
            )
        }
    }
}
