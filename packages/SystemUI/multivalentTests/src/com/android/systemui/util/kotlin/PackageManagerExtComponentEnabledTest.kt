/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.util.kotlin

import android.content.ComponentName
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
internal class PackageManagerExtComponentEnabledTest(private val testCase: TestCase) :
    SysuiTestCase() {

    @Mock private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testComponentActuallyEnabled() {
        whenever(packageManager.getComponentEnabledSetting(TEST_COMPONENT))
            .thenReturn(testCase.componentEnabledSetting)
        val componentInfo =
            mock<ComponentInfo>() {
                whenever(isEnabled).thenReturn(testCase.componentIsEnabled)
                whenever(componentName).thenReturn(TEST_COMPONENT)
            }

        assertThat(packageManager.isComponentActuallyEnabled(componentInfo))
            .isEqualTo(testCase.expected)
    }

    internal data class TestCase(
        @PackageManager.EnabledState val componentEnabledSetting: Int,
        val componentIsEnabled: Boolean,
        val expected: Boolean,
    ) {
        override fun toString(): String {
            return "WHEN(" +
                "componentIsEnabled = $componentIsEnabled, " +
                "componentEnabledSetting = ${enabledStateToString()}) then " +
                "EXPECTED = $expected"
        }

        private fun enabledStateToString() =
            when (componentEnabledSetting) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "STATE_DEFAULT"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "STATE_DISABLED"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> {
                    "STATE_DISABLED_UNTIL_USED"
                }
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "STATE_DISABLED_USER"
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "STATE_ENABLED"
                else -> "INVALID STATE"
            }
    }

    companion object {
        @Parameters(name = "{0}") @JvmStatic fun data(): Collection<TestCase> = testData

        private val testDataComponentIsEnabled =
            listOf(
                TestCase(
                    componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    componentIsEnabled = true,
                    expected = true,
                ),
                TestCase(
                    componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    componentIsEnabled = true,
                    expected = false,
                ),
                TestCase(
                    componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    componentIsEnabled = true,
                    expected = false,
                ),
                TestCase(
                    componentEnabledSetting =
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                    componentIsEnabled = true,
                    expected = false,
                ),
                TestCase(
                    componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    componentIsEnabled = true,
                    expected = true,
                ),
            )

        private val testDataComponentIsDisabled =
            listOf(
                TestCase(
                    componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    componentIsEnabled = false,
                    expected = true,
                ),
                TestCase(
                    componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    componentIsEnabled = false,
                    expected = false,
                ),
                TestCase(
                    componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    componentIsEnabled = false,
                    expected = false,
                ),
                TestCase(
                    componentEnabledSetting =
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                    componentIsEnabled = false,
                    expected = false,
                ),
                TestCase(
                    componentEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    componentIsEnabled = false,
                    expected = false,
                ),
            )

        private val testData = testDataComponentIsDisabled + testDataComponentIsEnabled

        private val TEST_COMPONENT = ComponentName("pkg", "cls")
    }
}
