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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import android.app.NotificationManager.Policy
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.statusbar.data.repository.fakeRemoteInputRepository
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.shared.NotificationsHeadsUpRefactor
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.notification.stack.data.repository.setNotifications
import com.android.systemui.statusbar.policy.data.repository.fakeUserSetupRepository
import com.android.systemui.statusbar.policy.data.repository.zenModeRepository
import com.android.systemui.statusbar.policy.fakeConfigurationController
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
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(FooterViewRefactor.FLAG_NAME)
class NotificationListViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope

    private val activeNotificationListRepository = kosmos.activeNotificationListRepository
    private val fakeConfigurationController = kosmos.fakeConfigurationController
    private val fakeKeyguardRepository = kosmos.fakeKeyguardRepository
    private val fakePowerRepository = kosmos.fakePowerRepository
    private val fakeRemoteInputRepository = kosmos.fakeRemoteInputRepository
    private val fakeShadeRepository = kosmos.fakeShadeRepository
    private val fakeUserSetupRepository = kosmos.fakeUserSetupRepository
    private val headsUpRepository = kosmos.headsUpNotificationRepository
    private val zenModeRepository = kosmos.zenModeRepository

    val underTest = kosmos.notificationListViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testIsImportantForAccessibility_falseWhenNoNotifs() =
        testScope.runTest {
            val important by collectLastValue(underTest.isImportantForAccessibility)

            // WHEN on lockscreen
            fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            // AND has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            testScope.runCurrent()

            // THEN not important
            assertThat(important).isFalse()
        }

    @Test
    fun testIsImportantForAccessibility_trueWhenNotifs() =
        testScope.runTest {
            val important by collectLastValue(underTest.isImportantForAccessibility)

            // WHEN on lockscreen
            fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            // AND has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            runCurrent()

            // THEN is important
            assertThat(important).isTrue()
        }

    @Test
    fun testIsImportantForAccessibility_trueWhenNotKeyguard() =
        testScope.runTest {
            val important by collectLastValue(underTest.isImportantForAccessibility)

            // WHEN not on lockscreen
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            // AND has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            runCurrent()

            // THEN is still important
            assertThat(important).isTrue()
        }

    @Test
    fun testShouldIncludeEmptyShadeView_trueWhenNoNotifs() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            runCurrent()

            // THEN empty shade is visible
            assertThat(shouldInclude).isTrue()
        }

    @Test
    fun testShouldIncludeEmptyShadeView_falseWhenNotifs() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            runCurrent()

            // THEN empty shade is not visible
            assertThat(shouldInclude).isFalse()
        }

    @Test
    fun testShouldIncludeEmptyShadeView_falseWhenQsExpandedDefault() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND quick settings are expanded
            fakeShadeRepository.legacyQsFullscreen.value = true
            runCurrent()

            // THEN empty shade is not visible
            assertThat(shouldInclude).isFalse()
        }

    @Test
    fun testShouldIncludeEmptyShadeView_trueWhenQsExpandedInSplitShade() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND quick settings are expanded
            fakeShadeRepository.setQsExpansion(1f)
            // AND split shade is enabled
            overrideResource(R.bool.config_use_split_notification_shade, true)
            fakeConfigurationController.notifyConfigurationChanged()
            runCurrent()

            // THEN empty shade is visible
            assertThat(shouldInclude).isTrue()
        }

    @Test
    fun testShouldIncludeEmptyShadeView_trueWhenLockedShade() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
            runCurrent()

            // THEN empty shade is visible
            assertThat(shouldInclude).isTrue()
        }

    @Test
    fun testShouldIncludeEmptyShadeView_falseWhenKeyguard() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            runCurrent()

            // THEN empty shade is not visible
            assertThat(shouldInclude).isFalse()
        }

    @Test
    fun testShouldIncludeEmptyShadeView_falseWhenStartingToSleep() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            // AND device is starting to go to sleep
            fakePowerRepository.updateWakefulness(WakefulnessState.STARTING_TO_SLEEP)
            runCurrent()

            // THEN empty shade is not visible
            assertThat(shouldInclude).isFalse()
        }

    @Test
    fun testAreNotificationsHiddenInShade_true() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.setSuppressedVisualEffects(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST)
            zenModeRepository.zenMode.value = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
            runCurrent()

            assertThat(hidden).isTrue()
        }

    @Test
    fun testAreNotificationsHiddenInShade_false() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.setSuppressedVisualEffects(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST)
            zenModeRepository.zenMode.value = Settings.Global.ZEN_MODE_OFF
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun testHasFilteredOutSeenNotifications_true() =
        testScope.runTest {
            val hasFilteredNotifs by collectLastValue(underTest.hasFilteredOutSeenNotifications)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true
            runCurrent()

            assertThat(hasFilteredNotifs).isTrue()
        }

    @Test
    fun testHasFilteredOutSeenNotifications_false() =
        testScope.runTest {
            val hasFilteredNotifs by collectLastValue(underTest.hasFilteredOutSeenNotifications)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = false
            runCurrent()

            assertThat(hasFilteredNotifs).isFalse()
        }

    @Test
    fun testShouldIncludeFooterView_trueWhenShade() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN footer is visible
            assertThat(shouldInclude?.value).isTrue()
        }

    @Test
    fun testShouldIncludeFooterView_trueWhenLockedShade() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND shade is open on lockscreen
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN footer is visible
            assertThat(shouldInclude?.value).isTrue()
        }

    @Test
    fun testShouldIncludeFooterView_falseWhenKeyguard() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND is on keyguard
            fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            runCurrent()

            // THEN footer is not visible
            assertThat(shouldInclude?.value).isFalse()
        }

    @Test
    fun testShouldIncludeFooterView_falseWhenUserNotSetUp() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            // AND user is not set up
            fakeUserSetupRepository.setUserSetUp(false)
            runCurrent()

            // THEN footer is not visible
            assertThat(shouldInclude?.value).isFalse()
        }

    @Test
    fun testShouldIncludeFooterView_falseWhenStartingToSleep() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            // AND device is starting to go to sleep
            fakePowerRepository.updateWakefulness(WakefulnessState.STARTING_TO_SLEEP)
            runCurrent()

            // THEN footer is not visible
            assertThat(shouldInclude?.value).isFalse()
        }

    @Test
    fun testShouldIncludeFooterView_falseWhenQsExpandedDefault() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            // AND quick settings are expanded
            fakeShadeRepository.setQsExpansion(1f)
            fakeShadeRepository.legacyQsFullscreen.value = true
            runCurrent()

            // THEN footer is not visible
            assertThat(shouldInclude?.value).isFalse()
        }

    @Test
    fun testShouldIncludeFooterView_trueWhenQsExpandedSplitShade() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            // AND quick settings are expanded
            fakeShadeRepository.setQsExpansion(1f)
            // AND split shade is enabled
            overrideResource(R.bool.config_use_split_notification_shade, true)
            fakeConfigurationController.notifyConfigurationChanged()
            runCurrent()

            // THEN footer is visible
            assertThat(shouldInclude?.value).isTrue()
        }

    @Test
    fun testShouldIncludeFooterView_falseWhenRemoteInputActive() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            // AND remote input is active
            fakeRemoteInputRepository.isRemoteInputActive.value = true
            runCurrent()

            // THEN footer is not visible
            assertThat(shouldInclude?.value).isFalse()
        }

    @Test
    fun testShouldIncludeFooterView_animatesWhenShade() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND shade is open and fully expanded
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN footer visibility animates
            assertThat(shouldInclude?.isAnimating).isTrue()
        }

    @Test
    fun testShouldIncludeFooterView_notAnimatingOnKeyguard() =
        testScope.runTest {
            val shouldInclude by collectLastValue(underTest.shouldIncludeFooterView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            // AND we are on the keyguard
            fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN footer visibility does not animate
            assertThat(shouldInclude?.isAnimating).isFalse()
        }

    @Test
    fun testShouldHideFooterView_trueWhenShadeIsClosed() =
        testScope.runTest {
            val shouldHide by collectLastValue(underTest.shouldHideFooterView)

            // WHEN shade is closed
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN footer is hidden
            assertThat(shouldHide).isTrue()
        }

    @Test
    fun testShouldHideFooterView_falseWhenShadeIsOpen() =
        testScope.runTest {
            val shouldHide by collectLastValue(underTest.shouldHideFooterView)

            // WHEN shade is open
            fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            fakeShadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN footer is hidden
            assertThat(shouldHide).isFalse()
        }

    @Test
    @EnableFlags(NotificationsHeadsUpRefactor.FLAG_NAME)
    fun testPinnedHeadsUpRows_filtersForPinnedItems() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRows)

            // WHEN there are no pinned rows
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository(key = "0"),
                    fakeHeadsUpRowRepository(key = "1"),
                    fakeHeadsUpRowRepository(key = "2"),
                )
            headsUpRepository.setNotifications(
                rows,
            )
            runCurrent()

            // THEN the list is empty
            assertThat(pinnedHeadsUpRows).isEmpty()

            // WHEN a row gets pinned
            rows[0].isPinned.value = true
            runCurrent()

            // THEN it's added to the list
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0])

            // WHEN more rows are pinned
            rows[1].isPinned.value = true
            runCurrent()

            // THEN they are all in the list
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0], rows[1])

            // WHEN a row gets unpinned
            rows[0].isPinned.value = false
            runCurrent()

            // THEN it's removed from the list
            assertThat(pinnedHeadsUpRows).containsExactly(rows[1])
        }

    @Test
    @EnableFlags(NotificationsHeadsUpRefactor.FLAG_NAME)
    fun testHasPinnedHeadsUpRows_true() =
        testScope.runTest {
            val hasPinnedHeadsUpRow by collectLastValue(underTest.hasPinnedHeadsUpRow)

            headsUpRepository.setNotifications(
                fakeHeadsUpRowRepository(key = "0", isPinned = true),
                fakeHeadsUpRowRepository(key = "1")
            )
            runCurrent()

            assertThat(hasPinnedHeadsUpRow).isTrue()
        }

    @Test
    @EnableFlags(NotificationsHeadsUpRefactor.FLAG_NAME)
    fun testHasPinnedHeadsUpRows_false() =
        testScope.runTest {
            val hasPinnedHeadsUpRow by collectLastValue(underTest.hasPinnedHeadsUpRow)

            headsUpRepository.setNotifications(
                fakeHeadsUpRowRepository(key = "0"),
                fakeHeadsUpRowRepository(key = "1"),
            )
            runCurrent()

            assertThat(hasPinnedHeadsUpRow).isFalse()
        }

    @Test
    @EnableFlags(NotificationsHeadsUpRefactor.FLAG_NAME)
    fun testTopHeadsUpRow_emptyList_null() =
        testScope.runTest {
            val topHeadsUpRow by collectLastValue(underTest.topHeadsUpRow)

            headsUpRepository.setNotifications(emptyList())
            runCurrent()

            assertThat(topHeadsUpRow).isNull()
        }

    @Test
    @EnableFlags(NotificationsHeadsUpRefactor.FLAG_NAME)
    fun testHeadsUpAnimationsEnabled_true() =
        testScope.runTest {
            val animationsEnabled by collectLastValue(underTest.headsUpAnimationsEnabled)

            fakeShadeRepository.setQsExpansion(0.0f)
            fakeKeyguardRepository.setKeyguardShowing(false)
            runCurrent()

            assertThat(animationsEnabled).isTrue()
        }

    @Test
    @EnableFlags(NotificationsHeadsUpRefactor.FLAG_NAME)
    fun testHeadsUpAnimationsEnabled_keyguardShowing_false() =
        testScope.runTest {
            val animationsEnabled by collectLastValue(underTest.headsUpAnimationsEnabled)

            fakeShadeRepository.setQsExpansion(0.0f)
            fakeKeyguardRepository.setKeyguardShowing(true)
            runCurrent()

            assertThat(animationsEnabled).isFalse()
        }

    private fun fakeHeadsUpRowRepository(key: String, isPinned: Boolean = false) =
        FakeHeadsUpRowRepository(key = key, elementKey = Any()).apply {
            this.isPinned.value = isPinned
        }
}
