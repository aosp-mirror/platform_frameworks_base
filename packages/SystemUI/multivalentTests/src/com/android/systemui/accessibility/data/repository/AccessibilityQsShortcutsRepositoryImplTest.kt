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
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class AccessibilityQsShortcutsRepositoryImplTest : SysuiTestCase() {
    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()

    // mocks
    @Mock private lateinit var a11yManager: AccessibilityManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val secureSettings = FakeSettings()

    private val userA11yQsShortcutsRepositoryFactory =
        object : UserA11yQsShortcutsRepository.Factory {
            override fun create(userId: Int): UserA11yQsShortcutsRepository {
                return UserA11yQsShortcutsRepository(
                    userId,
                    secureSettings,
                    testScope.backgroundScope,
                    testDispatcher,
                )
            }
        }

    private lateinit var underTest: AccessibilityQsShortcutsRepositoryImpl

    @Before
    fun setUp() {
        underTest =
            AccessibilityQsShortcutsRepositoryImpl(
                a11yManager,
                userA11yQsShortcutsRepositoryFactory,
                testDispatcher
            )
    }

    @Test
    fun a11yQsShortcutTargetsForCorrectUsers() =
        testScope.runTest {
            val user0 = 0
            val targetsForUser0 = setOf("a", "b", "c")
            val user1 = 1
            val targetsForUser1 = setOf("A")
            val targetsFromUser0 by collectLastValue(underTest.a11yQsShortcutTargets(user0))
            val targetsFromUser1 by collectLastValue(underTest.a11yQsShortcutTargets(user1))

            storeA11yQsShortcutTargetsForUser(targetsForUser0, user0)
            storeA11yQsShortcutTargetsForUser(targetsForUser1, user1)

            assertThat(targetsFromUser0).isEqualTo(targetsForUser0)
            assertThat(targetsFromUser1).isEqualTo(targetsForUser1)
        }

    private fun storeA11yQsShortcutTargetsForUser(a11yQsTargets: Set<String>, forUser: Int) {
        secureSettings.putStringForUser(
            SETTING_NAME,
            a11yQsTargets.joinToString(separator = ":"),
            forUser
        )
    }

    companion object {
        private const val SETTING_NAME = Settings.Secure.ACCESSIBILITY_QS_TARGETS
    }
}
