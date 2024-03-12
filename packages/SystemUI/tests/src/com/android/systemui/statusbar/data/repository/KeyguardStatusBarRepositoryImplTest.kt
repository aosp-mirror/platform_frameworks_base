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

package com.android.systemui.statusbar.data.repository

import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.user.data.repository.FakeUserSwitcherRepository
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.verify

@SmallTest
class KeyguardStatusBarRepositoryImplTest : SysuiTestCase() {
    private val testScope = TestScope()
    private val configurationController = mock<ConfigurationController>()
    private val userSwitcherRepository = FakeUserSwitcherRepository()

    val underTest =
        KeyguardStatusBarRepositoryImpl(
            context,
            configurationController,
            userSwitcherRepository,
        )

    private val configurationListener: ConfigurationController.ConfigurationListener
        get() {
            val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
            verify(configurationController).addCallback(capture(captor))
            return captor.value
        }

    @Test
    fun isKeyguardUserSwitcherEnabled_switcherNotEnabled_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isKeyguardUserSwitcherEnabled)

            userSwitcherRepository.isEnabled.value = false

            assertThat(latest).isFalse()
        }

    @Test
    fun isKeyguardUserSwitcherEnabled_keyguardConfigNotEnabled_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isKeyguardUserSwitcherEnabled)
            userSwitcherRepository.isEnabled.value = true

            context.orCreateTestableResources.addOverride(R.bool.config_keyguardUserSwitcher, false)

            assertThat(latest).isFalse()
        }

    @Test
    fun isKeyguardUserSwitcherEnabled_switchEnabledAndKeyguardConfigEnabled_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isKeyguardUserSwitcherEnabled)

            userSwitcherRepository.isEnabled.value = true
            context.orCreateTestableResources.addOverride(R.bool.config_keyguardUserSwitcher, true)

            assertThat(latest).isTrue()
        }

    @Test
    fun isKeyguardUserSwitcherEnabled_refetchedOnSmallestWidthChanged() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isKeyguardUserSwitcherEnabled)
            userSwitcherRepository.isEnabled.value = true
            context.orCreateTestableResources.addOverride(R.bool.config_keyguardUserSwitcher, true)
            assertThat(latest).isTrue()

            context.orCreateTestableResources.addOverride(R.bool.config_keyguardUserSwitcher, false)
            configurationListener.onSmallestScreenWidthChanged()

            assertThat(latest).isFalse()
        }

    @Test
    fun isKeyguardUserSwitcherEnabled_refetchedOnDensityChanged() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isKeyguardUserSwitcherEnabled)
            userSwitcherRepository.isEnabled.value = true
            context.orCreateTestableResources.addOverride(R.bool.config_keyguardUserSwitcher, true)
            assertThat(latest).isTrue()

            context.orCreateTestableResources.addOverride(R.bool.config_keyguardUserSwitcher, false)
            configurationListener.onDensityOrFontScaleChanged()

            assertThat(latest).isFalse()
        }

    @Test
    fun isKeyguardUserSwitcherEnabled_refetchedOnEnabledChanged() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isKeyguardUserSwitcherEnabled)

            userSwitcherRepository.isEnabled.value = false
            context.orCreateTestableResources.addOverride(R.bool.config_keyguardUserSwitcher, true)
            assertThat(latest).isFalse()

            // WHEN the switcher becomes enabled but the keyguard switcher becomes disabled
            context.orCreateTestableResources.addOverride(R.bool.config_keyguardUserSwitcher, false)
            userSwitcherRepository.isEnabled.value = true

            // THEN the value is still false because the keyguard config is refetched
            assertThat(latest).isFalse()
        }
}
