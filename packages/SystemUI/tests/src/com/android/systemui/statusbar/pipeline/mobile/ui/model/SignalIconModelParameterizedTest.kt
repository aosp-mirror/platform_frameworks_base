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

package com.android.systemui.statusbar.pipeline.mobile.ui.model

import androidx.test.filters.SmallTest
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

@SmallTest
@RunWith(Parameterized::class)
internal class SignalIconModelParameterizedTest(private val testCase: TestCase) : SysuiTestCase() {
    @Test
    fun drawableFromModel_level0_numLevels4_noExclamation_notCarrierNetworkChange() {
        val model =
            SignalIconModel.Cellular(
                level = 0,
                numberOfLevels = 4,
                showExclamationMark = false,
                carrierNetworkChange = false
            )

        val expected =
            SignalDrawable.getState(/* level = */ 0, /* numLevels = */ 4, /* cutOut = */ false)

        assertThat(model.toSignalDrawableState()).isEqualTo(expected)
    }

    @Test
    fun runTest() {
        val model = testCase.toSignalIconModel()
        assertThat(model.toSignalDrawableState()).isEqualTo(testCase.expected)
    }

    internal data class TestCase(
        val level: Int,
        val numberOfLevels: Int,
        val showExclamation: Boolean,
        val carrierNetworkChange: Boolean,
        val expected: Int,
    ) {
        fun toSignalIconModel() =
            SignalIconModel.Cellular(
                level = level,
                numberOfLevels = numberOfLevels,
                showExclamationMark = showExclamation,
                carrierNetworkChange = carrierNetworkChange,
            )

        override fun toString(): String =
            "INPUT(level=$level," +
                "numberOfLevels=$numberOfLevels," +
                "showExclamation=$showExclamation," +
                "carrierNetworkChange=$carrierNetworkChange)"
    }

    companion object {
        @Parameters(name = "{0}") @JvmStatic fun data() = testData()

        private fun testData(): Collection<TestCase> =
            listOf(
                TestCase(
                    level = 0,
                    numberOfLevels = 4,
                    showExclamation = false,
                    carrierNetworkChange = false,
                    expected = SignalDrawable.getState(0, 4, false)
                ),
                TestCase(
                    level = 0,
                    numberOfLevels = 4,
                    showExclamation = false,
                    carrierNetworkChange = true,
                    expected = SignalDrawable.getCarrierChangeState(4)
                ),
                TestCase(
                    level = 2,
                    numberOfLevels = 5,
                    showExclamation = false,
                    carrierNetworkChange = false,
                    expected = SignalDrawable.getState(2, 5, false)
                ),
                TestCase(
                    level = 2,
                    numberOfLevels = 5,
                    showExclamation = true,
                    carrierNetworkChange = false,
                    expected = SignalDrawable.getState(2, 5, true)
                ),
                TestCase(
                    level = 2,
                    numberOfLevels = 5,
                    showExclamation = true,
                    carrierNetworkChange = true,
                    expected = SignalDrawable.getCarrierChangeState(5)
                ),
            )
    }
}
