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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardLongPressInteractorTest : SysuiTestCase() {

    @Mock private lateinit var logger: UiEventLogger
    @Mock private lateinit var accessibilityManager: AccessibilityManagerWrapper

    private lateinit var underTest: KeyguardLongPressInteractor

    private lateinit var testScope: TestScope
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        overrideResource(R.bool.long_press_keyguard_customize_lockscreen_enabled, true)
        whenever(accessibilityManager.getRecommendedTimeoutMillis(anyInt(), anyInt())).thenAnswer {
            it.arguments[0]
        }

        testScope = TestScope()
        keyguardRepository = FakeKeyguardRepository()
        keyguardTransitionRepository = FakeKeyguardTransitionRepository()

        runBlocking { createUnderTest() }
    }

    @After
    fun tearDown() {
        mContext
            .getOrCreateTestableResources()
            .removeOverride(R.bool.long_press_keyguard_customize_lockscreen_enabled)
    }

    @Test
    fun isEnabled() =
        testScope.runTest {
            val isEnabled = collectLastValue(underTest.isLongPressHandlingEnabled)
            KeyguardState.values().forEach { keyguardState ->
                setUpState(
                    keyguardState = keyguardState,
                )

                if (keyguardState == KeyguardState.LOCKSCREEN) {
                    assertThat(isEnabled()).isTrue()
                } else {
                    assertThat(isEnabled()).isFalse()
                }
            }
        }

    @Test
    fun isEnabled_alwaysFalseWhenQuickSettingsAreVisible() =
        testScope.runTest {
            val isEnabled = collectLastValue(underTest.isLongPressHandlingEnabled)
            KeyguardState.values().forEach { keyguardState ->
                setUpState(
                    keyguardState = keyguardState,
                    isQuickSettingsVisible = true,
                )

                assertThat(isEnabled()).isFalse()
            }
        }

    @Test
    fun isEnabled_alwaysFalseWhenConfigEnabledBooleanIsFalse() =
        testScope.runTest {
            overrideResource(R.bool.long_press_keyguard_customize_lockscreen_enabled, false)
            createUnderTest()
            val isEnabled by collectLastValue(underTest.isLongPressHandlingEnabled)
            runCurrent()

            assertThat(isEnabled).isFalse()
        }

    @Test
    fun longPressed_menuClicked_showsSettings() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            val shouldOpenSettings by collectLastValue(underTest.shouldOpenSettings)
            runCurrent()

            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()

            underTest.onMenuTouchGestureEnded(/* isClick= */ true)

            assertThat(isMenuVisible).isFalse()
            assertThat(shouldOpenSettings).isTrue()
        }

    @Test
    fun onSettingsShown_consumesSettingsShowEvent() =
        testScope.runTest {
            val shouldOpenSettings by collectLastValue(underTest.shouldOpenSettings)
            runCurrent()

            underTest.onLongPress()
            underTest.onMenuTouchGestureEnded(/* isClick= */ true)
            assertThat(shouldOpenSettings).isTrue()

            underTest.onSettingsShown()
            assertThat(shouldOpenSettings).isFalse()
        }

    @Test
    fun onTouchedOutside_neverShowsSettings() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            val shouldOpenSettings by collectLastValue(underTest.shouldOpenSettings)
            runCurrent()

            underTest.onTouchedOutside()

            assertThat(isMenuVisible).isFalse()
            assertThat(shouldOpenSettings).isFalse()
        }

    @Test
    fun longPressed_openWppDirectlyEnabled_doesNotShowMenu_opensSettings() =
        testScope.runTest {
            createUnderTest(isOpenWppDirectlyEnabled = true)
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            val shouldOpenSettings by collectLastValue(underTest.shouldOpenSettings)
            runCurrent()

            underTest.onLongPress()

            assertThat(isMenuVisible).isFalse()
            assertThat(shouldOpenSettings).isTrue()
        }

    @Test
    fun longPressed_closeDialogsBroadcastReceived_popupDismissed() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            )

            assertThat(isMenuVisible).isFalse()
        }

    @Test
    fun closesDialogAfterTimeout() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()

            advanceTimeBy(KeyguardLongPressInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)

            assertThat(isMenuVisible).isFalse()
        }

    @Test
    fun closesDialogAfterTimeout_onlyAfterTouchGestureEnded() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()
            underTest.onMenuTouchGestureStarted()

            advanceTimeBy(KeyguardLongPressInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(isMenuVisible).isTrue()

            underTest.onMenuTouchGestureEnded(/* isClick= */ false)
            advanceTimeBy(KeyguardLongPressInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(isMenuVisible).isFalse()
        }

    @Test
    fun logsWhenMenuIsShown() =
        testScope.runTest {
            collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()

            verify(logger)
                .log(KeyguardLongPressInteractor.LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN)
        }

    @Test
    fun logsWhenMenuIsClicked() =
        testScope.runTest {
            collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            underTest.onMenuTouchGestureEnded(/* isClick= */ true)

            verify(logger)
                .log(KeyguardLongPressInteractor.LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED)
        }

    @Test
    fun showMenu_leaveLockscreen_returnToLockscreen_menuNotVisible() =
        testScope.runTest {
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            runCurrent()
            underTest.onLongPress()
            assertThat(isMenuVisible).isTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )
            assertThat(isMenuVisible).isFalse()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope
            )
            assertThat(isMenuVisible).isFalse()
        }

    private suspend fun createUnderTest(
        isLongPressFeatureEnabled: Boolean = true,
        isRevampedWppFeatureEnabled: Boolean = true,
        isOpenWppDirectlyEnabled: Boolean = false,
    ) {
        underTest =
            KeyguardLongPressInteractor(
                appContext = mContext,
                scope = testScope.backgroundScope,
                transitionInteractor =
                    KeyguardTransitionInteractorFactory.create(
                            scope = testScope.backgroundScope,
                            repository = keyguardTransitionRepository,
                        )
                        .keyguardTransitionInteractor,
                repository = keyguardRepository,
                logger = logger,
                featureFlags =
                    FakeFeatureFlags().apply {
                        set(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED, isLongPressFeatureEnabled)
                        set(Flags.LOCK_SCREEN_LONG_PRESS_DIRECT_TO_WPP, isOpenWppDirectlyEnabled)
                    },
                broadcastDispatcher = fakeBroadcastDispatcher,
                accessibilityManager = accessibilityManager
            )
        setUpState()
    }

    private suspend fun setUpState(
        keyguardState: KeyguardState = KeyguardState.LOCKSCREEN,
        isQuickSettingsVisible: Boolean = false,
    ) {
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = keyguardState,
            testScope = testScope
        )
        keyguardRepository.setQuickSettingsVisible(isVisible = isQuickSettingsVisible)
    }
}
