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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.footer.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.powerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shared.settings.data.repository.fakeSecureSettingsRepository
import com.android.systemui.statusbar.notification.collection.render.NotifStats
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.testKosmos
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
@EnableFlags(FooterViewRefactor.FLAG_NAME)
class FooterViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository
    private val fakeKeyguardRepository = kosmos.fakeKeyguardRepository
    private val shadeRepository = kosmos.shadeRepository
    private val powerRepository = kosmos.powerRepository
    private val fakeSecureSettingsRepository = kosmos.fakeSecureSettingsRepository

    val underTest = kosmos.footerViewModel

    @Test
    fun testMessageVisible_whenFilteredNotifications() =
        testScope.runTest {
            val visible by collectLastValue(underTest.message.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true

            assertThat(visible).isTrue()
        }

    @Test
    fun testMessageVisible_whenNoFilteredNotifications() =
        testScope.runTest {
            val visible by collectLastValue(underTest.message.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = false

            assertThat(visible).isFalse()
        }

    @Test
    fun testClearAllButtonVisible_whenHasClearableNotifs() =
        testScope.runTest {
            val visible by collectLastValue(underTest.clearAllButton.isVisible)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = true,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            assertThat(visible?.value).isTrue()
        }

    @Test
    fun testClearAllButtonVisible_whenHasNoClearableNotifs() =
        testScope.runTest {
            val visible by collectLastValue(underTest.clearAllButton.isVisible)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = false,
                )
            runCurrent()

            assertThat(visible?.value).isFalse()
        }

    @Test
    fun testClearAllButtonAnimating_whenShadeExpandedAndTouchable() =
        testScope.runTest {
            val visible by collectLastValue(underTest.clearAllButton.isVisible)
            runCurrent()

            // WHEN shade is expanded
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setLegacyShadeExpansion(1f)
            // AND QS not expanded
            shadeRepository.setQsExpansion(0f)
            // AND device is awake
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            runCurrent()

            // AND there are clearable notifications
            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = true,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            // THEN button visibility should animate
            assertThat(visible?.isAnimating).isTrue()
        }

    @Test
    fun testClearAllButtonAnimating_whenShadeNotExpanded() =
        testScope.runTest {
            val visible by collectLastValue(underTest.clearAllButton.isVisible)
            runCurrent()

            // WHEN shade is collapsed
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setLegacyShadeExpansion(0f)
            // AND QS not expanded
            shadeRepository.setQsExpansion(0f)
            // AND device is awake
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            runCurrent()

            // AND there are clearable notifications
            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = true,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            // THEN button visibility should not animate
            assertThat(visible?.isAnimating).isFalse()
        }

    @Test
    fun testManageButton_whenHistoryDisabled() =
        testScope.runTest {
            val buttonLabel by collectLastValue(underTest.manageOrHistoryButton.labelId)
            runCurrent()

            // WHEN notification history is disabled
            fakeSecureSettingsRepository.setInt(Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0)

            // THEN label is "Manage"
            assertThat(buttonLabel).isEqualTo(R.string.manage_notifications_text)
        }

    @Test
    fun testHistoryButton_whenHistoryEnabled() =
        testScope.runTest {
            val buttonLabel by collectLastValue(underTest.manageOrHistoryButton.labelId)
            runCurrent()

            // WHEN notification history is disabled
            fakeSecureSettingsRepository.setInt(Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 1)

            // THEN label is "History"
            assertThat(buttonLabel).isEqualTo(R.string.manage_notifications_history_text)
        }
}
