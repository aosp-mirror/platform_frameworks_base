/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shade.data.repository

import android.provider.Settings.Global.DEVELOPMENT_SHADE_DISPLAY_AWARENESS
import android.view.Display
import android.view.Display.TYPE_EXTERNAL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.display.data.repository.display
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.shade.display.AnyExternalShadeDisplayPolicy
import com.android.systemui.shade.display.DefaultDisplayShadePolicy
import com.android.systemui.shade.display.FakeShadeDisplayPolicy
import com.android.systemui.shade.display.FocusShadeDisplayPolicy
import com.android.systemui.shade.display.StatusBarTouchShadeDisplayPolicy
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.fakeGlobalSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeDisplaysRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val globalSettings = kosmos.fakeGlobalSettings
    private val displayRepository = kosmos.displayRepository
    private val defaultPolicy = DefaultDisplayShadePolicy()
    private val policies = kosmos.shadeDisplayPolicies
    private val keyguardRepository = kosmos.fakeKeyguardRepository

    private fun createUnderTest(shadeOnDefaultDisplayWhenLocked: Boolean = false) =
        ShadeDisplaysRepositoryImpl(
            globalSettings,
            defaultPolicy,
            testScope.backgroundScope,
            policies,
            shadeOnDefaultDisplayWhenLocked = shadeOnDefaultDisplayWhenLocked,
            keyguardRepository,
        )

    @Test
    fun policy_changing_propagatedFromTheLatestPolicy() =
        testScope.runTest {
            val underTest = createUnderTest()
            val displayIds by collectValues(underTest.displayId)

            assertThat(displayIds).containsExactly(0)

            globalSettings.putString(DEVELOPMENT_SHADE_DISPLAY_AWARENESS, "any_external_display")

            displayRepository.addDisplay(displayId = 1)

            assertThat(displayIds).containsExactly(0, 1)

            displayRepository.addDisplay(displayId = 2)

            assertThat(displayIds).containsExactly(0, 1)

            displayRepository.removeDisplay(displayId = 1)

            assertThat(displayIds).containsExactly(0, 1, 2)

            globalSettings.putString(DEVELOPMENT_SHADE_DISPLAY_AWARENESS, "default_display")

            assertThat(displayIds).containsExactly(0, 1, 2, 0)
        }

    @Test
    fun policy_updatesBasedOnSettingValue_defaultDisplay() =
        testScope.runTest {
            val underTest = createUnderTest()
            globalSettings.putString(DEVELOPMENT_SHADE_DISPLAY_AWARENESS, "default_display")

            assertThat(underTest.currentPolicy).isInstanceOf(DefaultDisplayShadePolicy::class.java)
        }

    @Test
    fun policy_updatesBasedOnSettingValue_anyExternal() =
        testScope.runTest {
            val underTest = createUnderTest()
            globalSettings.putString(DEVELOPMENT_SHADE_DISPLAY_AWARENESS, "any_external_display")

            assertThat(underTest.currentPolicy)
                .isInstanceOf(AnyExternalShadeDisplayPolicy::class.java)
        }

    @Test
    fun policy_updatesBasedOnSettingValue_lastStatusBarTouch() =
        testScope.runTest {
            val underTest = createUnderTest()
            globalSettings.putString(DEVELOPMENT_SHADE_DISPLAY_AWARENESS, "status_bar_latest_touch")

            assertThat(underTest.currentPolicy)
                .isInstanceOf(StatusBarTouchShadeDisplayPolicy::class.java)
        }

    @Test
    fun policy_updatesBasedOnSettingValue_focusBased() =
        testScope.runTest {
            val underTest = createUnderTest()
            globalSettings.putString(DEVELOPMENT_SHADE_DISPLAY_AWARENESS, "focused_display")

            assertThat(underTest.currentPolicy).isInstanceOf(FocusShadeDisplayPolicy::class.java)
        }

    @Test
    fun displayId_afterKeyguardHides_goesBackToPreviousDisplay() =
        testScope.runTest {
            val underTest = createUnderTest(shadeOnDefaultDisplayWhenLocked = true)
            globalSettings.putString(
                DEVELOPMENT_SHADE_DISPLAY_AWARENESS,
                FakeShadeDisplayPolicy.name,
            )

            val displayId by collectLastValue(underTest.displayId)

            displayRepository.addDisplays(display(id = 2, type = TYPE_EXTERNAL))
            FakeShadeDisplayPolicy.setDisplayId(2)

            assertThat(displayId).isEqualTo(2)

            keyguardRepository.setKeyguardShowing(true)

            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)

            keyguardRepository.setKeyguardShowing(false)

            assertThat(displayId).isEqualTo(2)
        }
}
