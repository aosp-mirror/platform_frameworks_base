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

import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.SmallTest
import com.android.systemui.FakeFeatureFlagsImpl
import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.flags.ReleasedFlag
import com.android.systemui.flags.ResourceBooleanFlag
import com.android.systemui.flags.UnreleasedFlag
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.media.controls.util.MediaInSceneContainerFlag
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@SmallTest
@RunWith(Parameterized::class)
internal class SceneContainerFlagsTest(
    private val testCase: TestCase,
) : SysuiTestCase() {

    @Rule @JvmField val setFlagsRule: SetFlagsRule = SetFlagsRule()

    private lateinit var underTest: SceneContainerFlags

    @Before
    fun setUp() {
        // TODO(b/283300105): remove this reflection setting once the hard-coded
        //  Flags.SCENE_CONTAINER_ENABLED is no longer needed.
        val field = Flags::class.java.getField("SCENE_CONTAINER_ENABLED")
        field.isAccessible = true
        field.set(null, true)

        val featureFlags =
            FakeFeatureFlagsClassic().apply {
                SceneContainerFlagsImpl.classicFlagTokens.forEach { flagToken ->
                    when (flagToken) {
                        is ResourceBooleanFlag -> set(flagToken, testCase.areAllFlagsSet)
                        is ReleasedFlag -> set(flagToken, testCase.areAllFlagsSet)
                        is UnreleasedFlag -> set(flagToken, testCase.areAllFlagsSet)
                        else -> error("Unsupported flag type ${flagToken.javaClass}")
                    }
                }
            }
        // TODO(b/306421592): get the aconfig FeatureFlags from the SetFlagsRule.
        val aconfigFlags = FakeFeatureFlagsImpl()

        listOf(
                AconfigFlags.FLAG_SCENE_CONTAINER,
                AconfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR,
                KeyguardShadeMigrationNssl.FLAG_NAME,
                MediaInSceneContainerFlag.FLAG_NAME,
            )
            .forEach { flagToken ->
                setFlagsRule.enableFlags(flagToken)
                aconfigFlags.setFlag(flagToken, testCase.areAllFlagsSet)
                overrideResource(
                    R.bool.config_sceneContainerFrameworkEnabled,
                    testCase.isResourceConfigEnabled
                )
            }

        underTest =
            SceneContainerFlagsImpl(
                context = context,
                featureFlagsClassic = featureFlags,
                isComposeAvailable = testCase.isComposeAvailable,
            )
    }

    @Test
    fun isEnabled() {
        assertThat(underTest.isEnabled()).isEqualTo(testCase.expectedEnabled)
    }

    internal data class TestCase(
        val isComposeAvailable: Boolean,
        val areAllFlagsSet: Boolean,
        val isResourceConfigEnabled: Boolean,
        val expectedEnabled: Boolean,
    ) {
        override fun toString(): String {
            return "(compose=$isComposeAvailable + flags=$areAllFlagsSet) + XML" +
                " config=$isResourceConfigEnabled -> expected=$expectedEnabled"
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun testCases() = buildList {
            repeat(8) { combination ->
                val isComposeAvailable = combination and 0b100 != 0
                val areAllFlagsSet = combination and 0b010 != 0
                val isResourceConfigEnabled = combination and 0b001 != 0

                val expectedEnabled =
                    isComposeAvailable && areAllFlagsSet && isResourceConfigEnabled

                add(
                    TestCase(
                        isComposeAvailable = isComposeAvailable,
                        areAllFlagsSet = areAllFlagsSet,
                        expectedEnabled = expectedEnabled,
                        isResourceConfigEnabled = isResourceConfigEnabled,
                    )
                )
            }
        }
    }
}
