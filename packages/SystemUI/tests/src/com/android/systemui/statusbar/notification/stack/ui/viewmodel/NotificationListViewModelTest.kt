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
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.TestMocksModule
import com.android.systemui.collectLastValue
import com.android.systemui.common.domain.CommonDomainLayerModule
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.res.R
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModelModule
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ActivatableNotificationViewModelModule
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.data.repository.FakeZenModeRepository
import com.android.systemui.unfold.UnfoldTransitionModule
import com.android.systemui.user.domain.interactor.HeadlessSystemUserModeModule
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationListViewModelTest : SysuiTestCase() {

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                ActivatableNotificationViewModelModule::class,
                CommonDomainLayerModule::class,
                FooterViewModelModule::class,
                HeadlessSystemUserModeModule::class,
                UnfoldTransitionModule.Bindings::class,
            ]
    )
    interface TestComponent : SysUITestComponent<NotificationListViewModel> {
        val activeNotificationListRepository: ActiveNotificationListRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val shadeRepository: FakeShadeRepository
        val zenModeRepository: FakeZenModeRepository
        val configurationController: FakeConfigurationController

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }

    private val testComponent: TestComponent =
        DaggerNotificationListViewModelTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        set(com.android.systemui.flags.Flags.FULL_SCREEN_USER_SWITCHER, true)
                    },
                mocks = TestMocksModule()
            )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATIONS_FOOTER_VIEW_REFACTOR)
    }

    @Test
    fun testIsImportantForAccessibility_falseWhenNoNotifs() =
        testComponent.runTest {
            val important by collectLastValue(underTest.isImportantForAccessibility)

            // WHEN on lockscreen
            keyguardTransitionRepository.sendTransitionSteps(
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
        testComponent.runTest {
            val important by collectLastValue(underTest.isImportantForAccessibility)

            // WHEN on lockscreen
            keyguardTransitionRepository.sendTransitionSteps(
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
        testComponent.runTest {
            val important by collectLastValue(underTest.isImportantForAccessibility)

            // WHEN not on lockscreen
            keyguardTransitionRepository.sendTransitionSteps(
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
        testComponent.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            runCurrent()

            // THEN should show
            assertThat(shouldShow).isTrue()
        }

    @Test
    fun testShouldShowEmptyShadeView_falseWhenNotifs() =
        testComponent.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has notifs
            activeNotificationListRepository.setActiveNotifs(count = 2)
            runCurrent()

            // THEN should not show
            assertThat(shouldShow).isFalse()
        }

    @Test
    fun testShouldShowEmptyShadeView_falseWhenQsExpandedDefault() =
        testComponent.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND quick settings are expanded
            shadeRepository.legacyQsFullscreen.value = true
            runCurrent()

            // THEN should not show
            assertThat(shouldShow).isFalse()
        }

    @Test
    fun testShouldShowEmptyShadeView_trueWhenQsExpandedInSplitShade() =
        testComponent.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND quick settings are expanded
            shadeRepository.setQsExpansion(1f)
            // AND split shade is enabled
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationController.notifyConfigurationChanged()
            runCurrent()

            // THEN should show
            assertThat(shouldShow).isTrue()
        }

    @Test
    fun testShouldShowEmptyShadeView_falseWhenTransitioningToAOD() =
        testComponent.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND transitioning to AOD
            keyguardTransitionRepository.sendTransitionStep(
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
        testComponent.runTest {
            val shouldShow by collectLastValue(underTest.shouldShowEmptyShadeView)

            // WHEN has no notifs
            activeNotificationListRepository.setActiveNotifs(count = 0)
            // AND is on bouncer
            keyguardTransitionRepository.sendTransitionSteps(
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
        testComponent.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.setSuppressedVisualEffects(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST)
            zenModeRepository.zenMode.value = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
            runCurrent()

            assertThat(hidden).isTrue()
        }

    @Test
    fun testAreNotificationsHiddenInShade_false() =
        testComponent.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.setSuppressedVisualEffects(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST)
            zenModeRepository.zenMode.value = Settings.Global.ZEN_MODE_OFF
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun testHasFilteredOutSeenNotifications_true() =
        testComponent.runTest {
            val hasFilteredNotifs by collectLastValue(underTest.hasFilteredOutSeenNotifications)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = true
            runCurrent()

            assertThat(hasFilteredNotifs).isTrue()
        }

    @Test
    fun testHasFilteredOutSeenNotifications_false() =
        testComponent.runTest {
            val hasFilteredNotifs by collectLastValue(underTest.hasFilteredOutSeenNotifications)

            activeNotificationListRepository.hasFilteredOutSeenNotifications.value = false
            runCurrent()

            assertThat(hasFilteredNotifs).isFalse()
        }
}
