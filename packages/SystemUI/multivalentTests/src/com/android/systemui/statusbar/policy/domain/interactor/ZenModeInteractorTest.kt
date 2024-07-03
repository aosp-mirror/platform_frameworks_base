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

package com.android.systemui.statusbar.policy.domain.interactor

import android.app.NotificationManager.Policy
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.statusbar.notification.data.repository.updateNotificationPolicy
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class ZenModeInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeZenModeRepository

    private val underTest = kosmos.zenModeInteractor

    @Test
    fun isZenModeEnabled_off() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.updateZenMode(Settings.Global.ZEN_MODE_OFF)
            runCurrent()

            assertThat(enabled).isFalse()
        }

    @Test
    fun isZenModeEnabled_alarms() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.updateZenMode(Settings.Global.ZEN_MODE_ALARMS)
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun isZenModeEnabled_importantInterruptions() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun isZenModeEnabled_noInterruptions() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.updateZenMode(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun testIsZenModeEnabled_unknown() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.updateZenMode(4) // this should fail if we ever add another zen mode type
            runCurrent()

            assertThat(enabled).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_noPolicy() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            repository.updateNotificationPolicy(null)
            repository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOffShadeSuppressed() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            repository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            repository.updateZenMode(Settings.Global.ZEN_MODE_OFF)
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOnShadeNotSuppressed() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            repository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_STATUS_BAR
            )
            repository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOnShadeSuppressed() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            repository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            repository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(hidden).isTrue()
        }
}
