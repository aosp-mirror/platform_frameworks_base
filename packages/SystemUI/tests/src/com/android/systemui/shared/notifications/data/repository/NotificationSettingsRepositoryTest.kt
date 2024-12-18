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

package com.android.systemui.shared.notifications.data.repository

import android.provider.Settings
import android.provider.Settings.Secure.ZEN_DURATION_FOREVER
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.android.systemui.shared.settings.data.repository.FakeSystemSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationSettingsRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: NotificationSettingsRepository

    private lateinit var testScope: TestScope
    private lateinit var secureSettingsRepository: FakeSecureSettingsRepository
    private lateinit var systemSettingsRepository: FakeSystemSettingsRepository

    @Before
    fun setUp() {
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        secureSettingsRepository = FakeSecureSettingsRepository()
        systemSettingsRepository = FakeSystemSettingsRepository()

        underTest =
            NotificationSettingsRepository(
                backgroundScope = testScope.backgroundScope,
                backgroundDispatcher = testDispatcher,
                secureSettingsRepository = secureSettingsRepository,
                systemSettingsRepository = systemSettingsRepository,
            )
    }

    @Test
    fun getIsShowNotificationsOnLockscreenEnabled() =
        testScope.runTest {
            val showNotifs by collectLastValue(underTest.isShowNotificationsOnLockScreenEnabled())

            secureSettingsRepository.setInt(
                name = Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                value = 1,
            )
            assertThat(showNotifs).isEqualTo(true)

            secureSettingsRepository.setInt(
                name = Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                value = 0,
            )
            assertThat(showNotifs).isEqualTo(false)
        }

    @Test
    fun setIsShowNotificationsOnLockscreenEnabled() =
        testScope.runTest {
            val showNotifs by collectLastValue(underTest.isShowNotificationsOnLockScreenEnabled())

            underTest.setShowNotificationsOnLockscreenEnabled(true)
            assertThat(showNotifs).isEqualTo(true)

            underTest.setShowNotificationsOnLockscreenEnabled(false)
            assertThat(showNotifs).isEqualTo(false)
        }

    @Test
    fun getIsNotificationHistoryEnabled() =
        testScope.runTest {
            val historyEnabled by collectLastValue(underTest.isNotificationHistoryEnabled)

            secureSettingsRepository.setInt(
                name = Settings.Secure.NOTIFICATION_HISTORY_ENABLED,
                value = 1,
            )
            assertThat(historyEnabled).isEqualTo(true)

            secureSettingsRepository.setInt(
                name = Settings.Secure.NOTIFICATION_HISTORY_ENABLED,
                value = 0,
            )
            assertThat(historyEnabled).isEqualTo(false)
        }

    @Test
    fun testGetIsCooldownEnabled() =
        testScope.runTest {
            val cooldownEnabled by collectLastValue(underTest.isCooldownEnabled)

            systemSettingsRepository.setInt(
                name = Settings.System.NOTIFICATION_COOLDOWN_ENABLED,
                value = 1,
            )
            assertThat(cooldownEnabled).isEqualTo(true)

            systemSettingsRepository.setInt(
                name = Settings.System.NOTIFICATION_COOLDOWN_ENABLED,
                value = 0,
            )
            assertThat(cooldownEnabled).isEqualTo(false)
        }

    @Test
    fun zenDuration() =
        testScope.runTest {
            val zenDuration by collectLastValue(underTest.zenDuration)

            secureSettingsRepository.setInt(
                name = Settings.Secure.ZEN_DURATION,
                value = 60,
            )
            assertThat(zenDuration).isEqualTo(60)

            secureSettingsRepository.setInt(
                name = Settings.Secure.ZEN_DURATION,
                value = ZEN_DURATION_FOREVER,
            )
            assertThat(zenDuration).isEqualTo(ZEN_DURATION_FOREVER)
        }
}
