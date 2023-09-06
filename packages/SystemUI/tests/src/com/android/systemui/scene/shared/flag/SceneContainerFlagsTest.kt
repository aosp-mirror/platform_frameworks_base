/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.scene.shared.flag

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.flags.ReleasedFlag
import com.android.systemui.flags.ResourceBooleanFlag
import com.android.systemui.flags.UnreleasedFlag
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@SmallTest
@RunWith(Parameterized::class)
internal class SceneContainerFlagsTest(
    private val testCase: TestCase,
) : SysuiTestCase() {

    private lateinit var underTest: SceneContainerFlags

    @Before
    fun setUp() {
        val featureFlags =
            FakeFeatureFlagsClassic().apply {
                SceneContainerFlagsImpl.flags.forEach { flag ->
                    when (flag) {
                        is ResourceBooleanFlag -> set(flag, testCase.areAllFlagsSet)
                        is ReleasedFlag -> set(flag, testCase.areAllFlagsSet)
                        is UnreleasedFlag -> set(flag, testCase.areAllFlagsSet)
                        else -> error("Unsupported flag type ${flag.javaClass}")
                    }
                }
            }
        underTest = SceneContainerFlagsImpl(featureFlags, testCase.isComposeAvailable)
    }

    @Test
    fun isEnabled() {
        assumeTrue(Flags.SCENE_CONTAINER_ENABLED)
        assertThat(underTest.isEnabled()).isEqualTo(testCase.expectedEnabled)
    }

    internal data class TestCase(
        val isComposeAvailable: Boolean,
        val areAllFlagsSet: Boolean,
        val expectedEnabled: Boolean,
    ) {
        override fun toString(): String {
            return """
                (compose=$isComposeAvailable + flags=$areAllFlagsSet) -> expected=$expectedEnabled
            """
                .trimIndent()
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun testCases() = buildList {
            repeat(4) { combination ->
                val isComposeAvailable = combination and 0b100 != 0
                val areAllFlagsSet = combination and 0b001 != 0

                val expectedEnabled = isComposeAvailable && areAllFlagsSet

                add(
                    TestCase(
                        isComposeAvailable = isComposeAvailable,
                        areAllFlagsSet = areAllFlagsSet,
                        expectedEnabled = expectedEnabled,
                    )
                )
            }
        }
    }
}
