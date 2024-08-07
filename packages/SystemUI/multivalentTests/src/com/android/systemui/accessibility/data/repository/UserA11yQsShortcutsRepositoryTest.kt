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

package com.android.systemui.accessibility.data.repository

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class UserA11yQsShortcutsRepositoryTest : SysuiTestCase() {
    private val secureSettings = FakeSettings()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val underTest =
        UserA11yQsShortcutsRepository(
            USER_ID,
            secureSettings,
            testScope.backgroundScope,
            testDispatcher
        )

    @Test
    fun targetsMatchesSetting() =
        testScope.runTest {
            val observedTargets by collectLastValue(underTest.targets)
            val a11yQsTargets = setOf("a", "b", "c")
            secureSettings.putStringForUser(
                SETTING_NAME,
                a11yQsTargets.joinToString(SEPARATOR),
                USER_ID
            )

            assertThat(observedTargets).isEqualTo(a11yQsTargets)
        }

    companion object {
        private const val USER_ID = 0
        private const val SEPARATOR = ":"
        private const val SETTING_NAME = Settings.Secure.ACCESSIBILITY_QS_TARGETS
    }
}
