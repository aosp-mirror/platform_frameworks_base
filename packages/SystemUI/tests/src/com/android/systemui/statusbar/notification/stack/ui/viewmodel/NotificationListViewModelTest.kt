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
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.policy.data.repository.zenModeRepository
import com.android.systemui.statusbar.policy.fakeConfigurationController
import com.android.systemui.testKosmos
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
    private val fakeKeyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val fakeShadeRepository = kosmos.fakeShadeRepository
    private val zenModeRepository = kosmos.zenModeRepository
    private val fakeConfigurationController = kosmos.fakeConfigurationController

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
            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )
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
            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )
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
            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope,
            )
            // AND has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            runCurrent()

            // THEN is still important
            assertThat(important).isTrue()
        }

    @Test
    fun testShouldShowEmptyShadeView_trueWhenNoNotifs() =
        testScope.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            runCurrent()

            // THEN should show
            assertThat(shouldShow).isTrue()
        }

    @Test
    fun testShouldShowEmptyShadeView_falseWhenNotifs() =
        testScope.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            runCurrent()

            // THEN should not show
            assertThat(shouldShow).isFalse()
        }

    @Test
    fun testShouldShowEmptyShadeView_falseWhenQsExpandedDefault() =
        testScope.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND quick settings are expanded
            fakeShadeRepository.legacyQsFullscreen.value = true
            runCurrent()

            // THEN should not show
            assertThat(shouldShow).isFalse()
        }

    @Test
    fun testShouldShowEmptyShadeView_trueWhenQsExpandedInSplitShade() =
        testScope.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND quick settings are expanded
            fakeShadeRepository.setQsExpansion(1f)
            // AND split shade is enabled
            overrideResource(R.bool.config_use_split_notification_shade, true)
            fakeConfigurationController.notifyConfigurationChanged()
            runCurrent()

            // THEN should show
            assertThat(shouldShow).isTrue()
        }

    @Test
    fun testShouldShowEmptyShadeView_falseWhenTransitioningToAOD() =
        testScope.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND transitioning to AOD
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 0f,
                )
            )
            runCurrent()

            // THEN should not show
            assertThat(shouldShow).isFalse()
        }

    @Test
    fun testShouldShowEmptyShadeView_falseWhenBouncerShowing() =
        testScope.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND is on bouncer
            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.PRIMARY_BOUNCER,
                testScope,
            )
            runCurrent()

            // THEN should not show
            assertThat(shouldShow).isFalse()
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
}
