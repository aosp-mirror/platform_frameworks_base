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
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.powerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.res.R
import com.android.systemui.shade.shadeTestUtil
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@RunWith(ParameterizedAndroidJunit4::class)
@SmallTest
@EnableFlags(FooterViewRefactor.FLAG_NAME)
class FooterViewModelTest(flags: FlagsParameterization?) : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository
    private val fakeKeyguardRepository = kosmos.fakeKeyguardRepository
    private val powerRepository = kosmos.powerRepository
    private val fakeSecureSettingsRepository = kosmos.fakeSecureSettingsRepository

    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }

    private lateinit var underTest: FooterViewModel

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setup() {
        underTest = kosmos.footerViewModel
    }

    @Test
    fun messageVisible_whenFilteredNotifications() =
        testScope.runTest {
            val visible by collectLastValue(underTest.message.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true

            assertThat(visible).isTrue()
        }

    @Test
    fun messageVisible_whenNoFilteredNotifications() =
        testScope.runTest {
            val visible by collectLastValue(underTest.message.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = false

            assertThat(visible).isFalse()
        }

    @Test
    fun clearAllButtonVisible_whenHasClearableNotifs() =
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
    fun clearAllButtonVisible_whenHasNoClearableNotifs() =
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
    fun clearAllButtonVisible_whenMessageVisible() =
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
            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true
            runCurrent()

            assertThat(visible?.value).isFalse()
        }

    @Test
    fun clearAllButtonAnimating_whenShadeExpandedAndTouchable() =
        testScope.runTest {
            val visible by collectLastValue(underTest.clearAllButton.isVisible)
            runCurrent()

            // WHEN shade is expanded AND QS not expanded
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeTestUtil.setShadeAndQsExpansion(1f, 0f)
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
    fun clearAllButtonAnimating_whenShadeNotExpanded() =
        testScope.runTest {
            val visible by collectLastValue(underTest.clearAllButton.isVisible)
            runCurrent()

            // WHEN shade is collapsed
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeTestUtil.setShadeExpansion(0f)
            // AND QS not expanded
            shadeTestUtil.setQsExpansion(0f)
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
    fun manageButton_whenHistoryDisabled() =
        testScope.runTest {
            val buttonLabel by collectLastValue(underTest.manageOrHistoryButton.labelId)
            runCurrent()

            // WHEN notification history is disabled
            fakeSecureSettingsRepository.setInt(Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0)

            // THEN label is "Manage"
            assertThat(buttonLabel).isEqualTo(R.string.manage_notifications_text)
        }

    @Test
    fun historyButton_whenHistoryEnabled() =
        testScope.runTest {
            val buttonLabel by collectLastValue(underTest.manageOrHistoryButton.labelId)
            runCurrent()

            // WHEN notification history is disabled
            fakeSecureSettingsRepository.setInt(Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 1)

            // THEN label is "History"
            assertThat(buttonLabel).isEqualTo(R.string.manage_notifications_history_text)
        }

    @Test
    fun manageButtonVisible_whenMessageVisible() =
        testScope.runTest {
            val visible by collectLastValue(underTest.manageOrHistoryButton.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true

            assertThat(visible?.value).isFalse()
        }

    @Test
    fun manageButtonVisible_whenMessageNotVisible() =
        testScope.runTest {
            val visible by collectLastValue(underTest.manageOrHistoryButton.isVisible)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = false

            assertThat(visible?.value).isTrue()
        }
}
