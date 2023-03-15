/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.controls.settings

import android.content.pm.UserInfo
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ControlsSettingsRepositoryImplTest : SysuiTestCase() {

    companion object {
        private const val LOCKSCREEN_SHOW = Settings.Secure.LOCKSCREEN_SHOW_CONTROLS
        private const val LOCKSCREEN_ACTION = Settings.Secure.LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS

        private fun createUser(id: Int): UserInfo {
            return UserInfo(id, "user_$id", 0)
        }

        private val ALL_USERS = (0..1).map { it to createUser(it) }.toMap()
    }

    private lateinit var underTest: ControlsSettingsRepository

    private lateinit var testScope: TestScope
    private lateinit var secureSettings: FakeSettings
    private lateinit var userRepository: FakeUserRepository

    @Before
    fun setUp() {
        secureSettings = FakeSettings()
        userRepository = FakeUserRepository()
        userRepository.setUserInfos(ALL_USERS.values.toList())

        val coroutineDispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(coroutineDispatcher)

        underTest =
            ControlsSettingsRepositoryImpl(
                scope = testScope.backgroundScope,
                backgroundDispatcher = coroutineDispatcher,
                userRepository = userRepository,
                secureSettings = secureSettings,
            )
    }

    @Test
    fun showInLockScreen() =
        testScope.runTest {
            setUser(0)
            val values = mutableListOf<Boolean>()
            val job =
                launch(UnconfinedTestDispatcher()) {
                    underTest.canShowControlsInLockscreen.toList(values)
                }
            assertThat(values.last()).isFalse()

            secureSettings.putBool(LOCKSCREEN_SHOW, true)
            assertThat(values.last()).isTrue()

            secureSettings.putBool(LOCKSCREEN_SHOW, false)
            assertThat(values.last()).isFalse()

            secureSettings.putBoolForUser(LOCKSCREEN_SHOW, true, 1)
            assertThat(values.last()).isFalse()

            setUser(1)
            assertThat(values.last()).isTrue()

            job.cancel()
        }

    @Test
    fun showInLockScreen_changesInOtherUsersAreNotQueued() =
        testScope.runTest {
            setUser(0)

            val values = mutableListOf<Boolean>()
            val job =
                launch(UnconfinedTestDispatcher()) {
                    underTest.canShowControlsInLockscreen.toList(values)
                }

            secureSettings.putBoolForUser(LOCKSCREEN_SHOW, true, 1)
            secureSettings.putBoolForUser(LOCKSCREEN_SHOW, false, 1)

            setUser(1)
            assertThat(values.last()).isFalse()
            assertThat(values).containsNoneIn(listOf(true))

            job.cancel()
        }

    @Test
    fun actionInLockScreen() =
        testScope.runTest {
            setUser(0)
            val values = mutableListOf<Boolean>()
            val job =
                launch(UnconfinedTestDispatcher()) {
                    underTest.allowActionOnTrivialControlsInLockscreen.toList(values)
                }
            assertThat(values.last()).isFalse()

            secureSettings.putBool(LOCKSCREEN_ACTION, true)
            assertThat(values.last()).isTrue()

            secureSettings.putBool(LOCKSCREEN_ACTION, false)
            assertThat(values.last()).isFalse()

            secureSettings.putBoolForUser(LOCKSCREEN_ACTION, true, 1)
            assertThat(values.last()).isFalse()

            setUser(1)
            assertThat(values.last()).isTrue()

            job.cancel()
        }

    @Test
    fun actionInLockScreen_changesInOtherUsersAreNotQueued() =
        testScope.runTest {
            setUser(0)

            val values = mutableListOf<Boolean>()
            val job =
                launch(UnconfinedTestDispatcher()) {
                    underTest.allowActionOnTrivialControlsInLockscreen.toList(values)
                }

            secureSettings.putBoolForUser(LOCKSCREEN_ACTION, true, 1)
            secureSettings.putBoolForUser(LOCKSCREEN_ACTION, false, 1)

            setUser(1)
            assertThat(values.last()).isFalse()
            assertThat(values).containsNoneIn(listOf(true))

            job.cancel()
        }

    @Test
    fun valueIsUpdatedWhenNotSubscribed() =
        testScope.runTest {
            setUser(0)
            assertThat(underTest.canShowControlsInLockscreen.value).isFalse()

            secureSettings.putBool(LOCKSCREEN_SHOW, true)

            assertThat(underTest.canShowControlsInLockscreen.value).isTrue()
        }

    private suspend fun setUser(id: Int) {
        secureSettings.userId = id
        userRepository.setSelectedUserInfo(ALL_USERS[id]!!)
    }
}
