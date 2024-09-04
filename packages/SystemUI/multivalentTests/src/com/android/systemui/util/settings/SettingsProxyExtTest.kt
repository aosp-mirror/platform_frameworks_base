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
package com.android.systemui.util.settings

import android.database.ContentObserver
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/** Tests for [SettingsProxyExt]. */
@RunWith(AndroidJUnit4::class)
@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProxyExtTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    @Mock lateinit var settingsProxy: SettingsProxy
    @Mock lateinit var userSettingsProxy: UserSettingsProxy

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    @EnableFlags(Flags.FLAG_SETTINGS_EXT_REGISTER_CONTENT_OBSERVER_ON_BG_THREAD)
    fun observeFlow_bgFlagEnabled_settingsProxy_registerContentObserverInvoked() =
        testScope.runTest {
            val unused by collectLastValue(settingsProxy.observerFlow(SETTING_1, SETTING_2))
            runCurrent()
            verify(settingsProxy, times(2))
                .registerContentObserver(any<String>(), any<ContentObserver>())
        }

    @Test
    @DisableFlags(Flags.FLAG_SETTINGS_EXT_REGISTER_CONTENT_OBSERVER_ON_BG_THREAD)
    fun observeFlow_bgFlagDisabled_multipleSettings_SettingsProxy_registerContentObserverInvoked() =
        testScope.runTest {
            val unused by collectLastValue(settingsProxy.observerFlow(SETTING_1, SETTING_2))
            runCurrent()
            verify(settingsProxy, times(2))
                .registerContentObserverSync(any<String>(), any<ContentObserver>())
        }

    @Test
    @EnableFlags(Flags.FLAG_SETTINGS_EXT_REGISTER_CONTENT_OBSERVER_ON_BG_THREAD)
    fun observeFlow_bgFlagEnabled_channelClosed_settingsProxy_unregisterContentObserverInvoked() =
        testScope.runTest {
            val job = Job()
            val unused by
                collectLastValue(settingsProxy.observerFlow(SETTING_1, SETTING_2), context = job)
            runCurrent()
            job.cancel()
            runCurrent()
            verify(settingsProxy).unregisterContentObserverAsync(any<ContentObserver>())
        }

    @Test
    @DisableFlags(Flags.FLAG_SETTINGS_EXT_REGISTER_CONTENT_OBSERVER_ON_BG_THREAD)
    fun observeFlow_bgFlagDisabled_channelClosed_settingsProxy_unregisterContentObserverInvoked() =
        testScope.runTest {
            val job = Job()
            val unused by
                collectLastValue(settingsProxy.observerFlow(SETTING_1, SETTING_2), context = job)
            runCurrent()
            job.cancel()
            runCurrent()
            verify(settingsProxy).unregisterContentObserverSync(any<ContentObserver>())
        }

    @Test
    @EnableFlags(Flags.FLAG_SETTINGS_EXT_REGISTER_CONTENT_OBSERVER_ON_BG_THREAD)
    fun observeFlow_bgFlagEnabled_userSettingsProxy_registerContentObserverForUserInvoked() =
        testScope.runTest {
            val unused by
                collectLastValue(userSettingsProxy.observerFlow(userId = 0, SETTING_1, SETTING_2))
            runCurrent()
            verify(userSettingsProxy, times(2))
                .registerContentObserverForUser(any<String>(), any<ContentObserver>(), any<Int>())
        }

    @Test
    @DisableFlags(Flags.FLAG_SETTINGS_EXT_REGISTER_CONTENT_OBSERVER_ON_BG_THREAD)
    fun observeFlow_bgFlagDisabled_userSettingsProxy_registerContentObserverForUserInvoked() =
        testScope.runTest {
            val unused by
                collectLastValue(userSettingsProxy.observerFlow(userId = 0, SETTING_1, SETTING_2))
            runCurrent()
            verify(userSettingsProxy, times(2))
                .registerContentObserverForUserSync(
                    any<String>(),
                    any<ContentObserver>(),
                    any<Int>()
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_SETTINGS_EXT_REGISTER_CONTENT_OBSERVER_ON_BG_THREAD)
    fun observeFlow_bgFlagEnabled_channelClosed_userSettingsProxy_unregisterContentObserverInvoked() =
        testScope.runTest {
            val job = Job()
            val unused by
                collectLastValue(
                    userSettingsProxy.observerFlow(userId = 0, SETTING_1, SETTING_2),
                    context = job
                )
            runCurrent()
            job.cancel()
            runCurrent()
            verify(userSettingsProxy).unregisterContentObserverAsync(any<ContentObserver>())
        }

    @Test
    @DisableFlags(Flags.FLAG_SETTINGS_EXT_REGISTER_CONTENT_OBSERVER_ON_BG_THREAD)
    fun observeFlow_bgFlagDisabled_channelClosed_userSettingsProxy_unregisterContentObserverInvoked() =
        testScope.runTest {
            val job = Job()
            val unused by
                collectLastValue(
                    userSettingsProxy.observerFlow(userId = 0, SETTING_1, SETTING_2),
                    context = job
                )
            runCurrent()
            job.cancel()
            runCurrent()
            verify(userSettingsProxy).unregisterContentObserverSync(any<ContentObserver>())
        }

    private companion object {
        val SETTING_1 = "settings_1"
        val SETTING_2 = "settings_2"
    }
}
