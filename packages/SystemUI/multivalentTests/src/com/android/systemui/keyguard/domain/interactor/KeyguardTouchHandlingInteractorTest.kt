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
import android.view.accessibility.accessibilityManagerWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.logging.uiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shade.pulsingGestureListener
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardTouchHandlingInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            this.accessibilityManagerWrapper = mock<AccessibilityManagerWrapper>()
            this.uiEventLogger = mock<UiEventLoggerFake>()
        }

    private lateinit var underTest: KeyguardTouchHandlingInteractor

    private val logger = kosmos.uiEventLogger
    private val testScope = kosmos.testScope
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        overrideResource(R.bool.long_press_keyguard_customize_lockscreen_enabled, true)
        whenever(kosmos.accessibilityManagerWrapper.getRecommendedTimeoutMillis(anyInt(), anyInt()))
            .thenAnswer { it.arguments[0] }

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
    fun longPressed_isA11yAction_doesNotShowMenu_opensSettings() =
        testScope.runTest {
            createUnderTest()
            val isMenuVisible by collectLastValue(underTest.isMenuVisible)
            val shouldOpenSettings by collectLastValue(underTest.shouldOpenSettings)
            val isA11yAction = true
            runCurrent()

            underTest.onLongPress(isA11yAction)

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

            advanceTimeBy(KeyguardTouchHandlingInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)

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

            advanceTimeBy(KeyguardTouchHandlingInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(isMenuVisible).isTrue()

            underTest.onMenuTouchGestureEnded(/* isClick= */ false)
            advanceTimeBy(KeyguardTouchHandlingInteractor.DEFAULT_POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(isMenuVisible).isFalse()
        }

    @Test
    fun logsWhenMenuIsShown() =
        testScope.runTest {
            collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()

            verify(logger)
                .log(KeyguardTouchHandlingInteractor.LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN)
        }

    @Test
    fun logsWhenMenuIsClicked() =
        testScope.runTest {
            collectLastValue(underTest.isMenuVisible)
            runCurrent()

            underTest.onLongPress()
            underTest.onMenuTouchGestureEnded(/* isClick= */ true)

            verify(logger)
                .log(KeyguardTouchHandlingInteractor.LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED)
        }

    @Test
    fun triggersFaceAuthWhenLockscreenIsClicked() =
        testScope.runTest {
            collectLastValue(underTest.isMenuVisible)
            runCurrent()
            kosmos.fakeDeviceEntryFaceAuthRepository.canRunFaceAuth.value = true

            underTest.onClick(100.0f, 100.0f)
            runCurrent()

            val runningAuthRequest =
                kosmos.fakeDeviceEntryFaceAuthRepository.runningAuthRequest.value
            assertThat(runningAuthRequest?.first)
                .isEqualTo(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED)
            assertThat(runningAuthRequest?.second).isEqualTo(true)
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
    ) {
        // This needs to be re-created for each test outside of kosmos since the flag values are
        // read during initialization to set up flows. Maybe there is a better way to handle that.
        underTest =
            KeyguardTouchHandlingInteractor(
                appContext = mContext,
                scope = testScope.backgroundScope,
                transitionInteractor = kosmos.keyguardTransitionInteractor,
                repository = keyguardRepository,
                logger = logger,
                featureFlags =
                    kosmos.fakeFeatureFlagsClassic.apply {
                        set(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED, isLongPressFeatureEnabled)
                    },
                broadcastDispatcher = fakeBroadcastDispatcher,
                accessibilityManager = kosmos.accessibilityManagerWrapper,
                pulsingGestureListener = kosmos.pulsingGestureListener,
                faceAuthInteractor = kosmos.deviceEntryFaceAuthInteractor,
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
