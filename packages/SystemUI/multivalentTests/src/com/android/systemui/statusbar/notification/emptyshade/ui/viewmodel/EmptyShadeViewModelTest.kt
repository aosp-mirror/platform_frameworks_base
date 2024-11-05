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

package com.android.systemui.statusbar.notification.emptyshade.ui.viewmodel

import android.app.Flags
import android.app.NotificationManager.Policy
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import android.service.notification.ZenPolicy.VISUAL_EFFECT_NOTIFICATION_LIST
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.data.repository.updateNotificationPolicy
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.settings.data.repository.fakeSecureSettingsRepository
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.policy.data.repository.zenModeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedAndroidJunit4::class)
@SmallTest
@EnableFlags(FooterViewRefactor.FLAG_NAME)
class EmptyShadeViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val zenModeRepository = kosmos.zenModeRepository
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository
    private val fakeSecureSettingsRepository = kosmos.fakeSecureSettingsRepository

    private val underTest = kosmos.emptyShadeViewModel

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Test
    fun areNotificationsHiddenInShade_true() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(hidden).isTrue()
        }

    @Test
    fun areNotificationsHiddenInShade_false() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_OFF)
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun hasFilteredOutSeenNotifications_true() =
        testScope.runTest {
            val hasFilteredNotifs by collectLastValue(underTest.hasFilteredOutSeenNotifications)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true
            runCurrent()

            assertThat(hasFilteredNotifs).isTrue()
        }

    @Test
    fun hasFilteredOutSeenNotifications_false() =
        testScope.runTest {
            val hasFilteredNotifs by collectLastValue(underTest.hasFilteredOutSeenNotifications)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = false
            runCurrent()

            assertThat(hasFilteredNotifs).isFalse()
        }

    @Test
    @EnableFlags(ModesEmptyShadeFix.FLAG_NAME)
    @DisableFlags(Flags.FLAG_MODES_UI, Flags.FLAG_MODES_API)
    fun text_changesWhenNotifsHiddenInShade() =
        testScope.runTest {
            val text by collectLastValue(underTest.text)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_OFF)
            runCurrent()

            assertThat(text).isEqualTo("No notifications")

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(text).isEqualTo("Notifications paused by Do Not Disturb")
        }

    @Test
    @EnableFlags(ModesEmptyShadeFix.FLAG_NAME, Flags.FLAG_MODES_UI, Flags.FLAG_MODES_API)
    fun text_reflectsModesHidingNotifications() =
        testScope.runTest {
            val text by collectLastValue(underTest.text)

            assertThat(text).isEqualTo("No notifications")

            zenModeRepository.addMode(
                TestModeBuilder()
                    .setId("Do not disturb")
                    .setName("Do not disturb")
                    .setActive(true)
                    .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                    .build()
            )
            runCurrent()
            assertThat(text).isEqualTo("Notifications paused by Do not disturb")

            zenModeRepository.addMode(
                TestModeBuilder()
                    .setId("Work")
                    .setName("Work")
                    .setActive(true)
                    .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                    .build()
            )
            runCurrent()
            assertThat(text).isEqualTo("Notifications paused by Do not disturb and one other mode")

            zenModeRepository.addMode(
                TestModeBuilder()
                    .setId("Gym")
                    .setName("Gym")
                    .setActive(true)
                    .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                    .build()
            )
            runCurrent()
            assertThat(text).isEqualTo("Notifications paused by Do not disturb and 2 other modes")

            zenModeRepository.deactivateMode("Do not disturb")
            zenModeRepository.deactivateMode("Work")
            runCurrent()
            assertThat(text).isEqualTo("Notifications paused by Gym")
        }

    @Test
    @EnableFlags(ModesEmptyShadeFix.FLAG_NAME)
    fun footer_isVisibleWhenSeenNotifsAreFilteredOut() =
        testScope.runTest {
            val footerVisible by collectLastValue(underTest.footer.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = false
            runCurrent()

            assertThat(footerVisible).isFalse()

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true
            runCurrent()

            assertThat(footerVisible).isTrue()
        }

    @Test
    @EnableFlags(ModesEmptyShadeFix.FLAG_NAME, Flags.FLAG_MODES_UI, Flags.FLAG_MODES_API)
    fun onClick_whenHistoryDisabled_leadsToSettingsPage() =
        testScope.runTest {
            val onClick by collectLastValue(underTest.onClick)
            runCurrent()

            fakeSecureSettingsRepository.setInt(Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0)

            assertThat(onClick?.targetIntent?.action)
                .isEqualTo(Settings.ACTION_NOTIFICATION_SETTINGS)
            assertThat(onClick?.backStack).isEmpty()
        }

    @Test
    @EnableFlags(ModesEmptyShadeFix.FLAG_NAME, Flags.FLAG_MODES_UI, Flags.FLAG_MODES_API)
    fun onClick_whenHistoryEnabled_leadsToHistoryPage() =
        testScope.runTest {
            val onClick by collectLastValue(underTest.onClick)
            runCurrent()

            fakeSecureSettingsRepository.setInt(Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 1)

            assertThat(onClick?.targetIntent?.action)
                .isEqualTo(Settings.ACTION_NOTIFICATION_HISTORY)
            assertThat(onClick?.backStack?.map { it.action })
                .containsExactly(Settings.ACTION_NOTIFICATION_SETTINGS)
        }

    @Test
    @EnableFlags(ModesEmptyShadeFix.FLAG_NAME, Flags.FLAG_MODES_UI, Flags.FLAG_MODES_API)
    fun onClick_whenOneModeHidingNotifications_leadsToModeSettings() =
        testScope.runTest {
            val onClick by collectLastValue(underTest.onClick)
            runCurrent()

            zenModeRepository.addMode(
                TestModeBuilder()
                    .setId("ID")
                    .setActive(true)
                    .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                    .build()
            )
            runCurrent()

            assertThat(onClick?.targetIntent?.action)
                .isEqualTo(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
            assertThat(
                    onClick?.targetIntent?.extras?.getString(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID)
                )
                .isEqualTo("ID")
            assertThat(onClick?.backStack?.map { it.action })
                .containsExactly(Settings.ACTION_ZEN_MODE_SETTINGS)
        }

    @Test
    @EnableFlags(ModesEmptyShadeFix.FLAG_NAME, Flags.FLAG_MODES_UI, Flags.FLAG_MODES_API)
    fun onClick_whenMultipleModesHidingNotifications_leadsToGeneralModesSettings() =
        testScope.runTest {
            val onClick by collectLastValue(underTest.onClick)
            runCurrent()

            zenModeRepository.addMode(
                TestModeBuilder()
                    .setActive(true)
                    .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                    .build()
            )
            zenModeRepository.addMode(
                TestModeBuilder()
                    .setActive(true)
                    .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                    .build()
            )
            runCurrent()

            assertThat(onClick?.targetIntent?.action).isEqualTo(Settings.ACTION_ZEN_MODE_SETTINGS)
            assertThat(onClick?.backStack).isEmpty()
        }
}
