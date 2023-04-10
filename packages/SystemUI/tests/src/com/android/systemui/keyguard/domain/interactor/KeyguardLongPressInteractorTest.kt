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
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardLongPressInteractorTest : SysuiTestCase() {

    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var logger: UiEventLogger

    private lateinit var underTest: KeyguardLongPressInteractor

    private lateinit var testScope: TestScope
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        runBlocking { createUnderTest() }
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
    fun `isEnabled - always false when quick settings are visible`() =
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
    fun `long-pressed - pop-up clicked - starts activity`() =
        testScope.runTest {
            val menu = collectLastValue(underTest.menu)
            runCurrent()

            val x = 100
            val y = 123
            underTest.onLongPress(x, y)
            assertThat(menu()).isNotNull()
            assertThat(menu()?.position?.x).isEqualTo(x)
            assertThat(menu()?.position?.y).isEqualTo(y)

            menu()?.onClicked?.invoke()

            assertThat(menu()).isNull()
            verify(activityStarter).dismissKeyguardThenExecute(any(), any(), anyBoolean())
        }

    @Test
    fun `long-pressed - pop-up dismissed - never starts activity`() =
        testScope.runTest {
            val menu = collectLastValue(underTest.menu)
            runCurrent()

            menu()?.onDismissed?.invoke()

            assertThat(menu()).isNull()
            verify(activityStarter, never()).dismissKeyguardThenExecute(any(), any(), anyBoolean())
        }

    @Suppress("DEPRECATION") // We're okay using ACTION_CLOSE_SYSTEM_DIALOGS on system UI.
    @Test
    fun `long pressed - close dialogs broadcast received - popup dismissed`() =
        testScope.runTest {
            val menu = collectLastValue(underTest.menu)
            runCurrent()

            underTest.onLongPress(123, 456)
            assertThat(menu()).isNotNull()

            fakeBroadcastDispatcher.registeredReceivers.forEach { broadcastReceiver ->
                broadcastReceiver.onReceive(context, Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            }

            assertThat(menu()).isNull()
        }

    @Test
    fun `logs when menu is shown`() =
        testScope.runTest {
            collectLastValue(underTest.menu)
            runCurrent()

            underTest.onLongPress(100, 123)

            verify(logger)
                .log(KeyguardLongPressInteractor.LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_SHOWN)
        }

    @Test
    fun `logs when menu is clicked`() =
        testScope.runTest {
            val menu = collectLastValue(underTest.menu)
            runCurrent()

            underTest.onLongPress(100, 123)
            menu()?.onClicked?.invoke()

            verify(logger)
                .log(KeyguardLongPressInteractor.LogEvents.LOCK_SCREEN_LONG_PRESS_POPUP_CLICKED)
        }

    private suspend fun createUnderTest(
        isLongPressFeatureEnabled: Boolean = true,
        isRevampedWppFeatureEnabled: Boolean = true,
    ) {
        testScope = TestScope()
        keyguardRepository = FakeKeyguardRepository()
        keyguardTransitionRepository = FakeKeyguardTransitionRepository()

        underTest =
            KeyguardLongPressInteractor(
                unsafeContext = context,
                scope = testScope.backgroundScope,
                transitionInteractor =
                    KeyguardTransitionInteractor(
                        repository = keyguardTransitionRepository,
                    ),
                repository = keyguardRepository,
                activityStarter = activityStarter,
                logger = logger,
                featureFlags =
                    FakeFeatureFlags().apply {
                        set(Flags.LOCK_SCREEN_LONG_PRESS_ENABLED, isLongPressFeatureEnabled)
                        set(Flags.REVAMPED_WALLPAPER_UI, isRevampedWppFeatureEnabled)
                    },
                broadcastDispatcher = fakeBroadcastDispatcher,
            )
        setUpState()
    }

    private suspend fun setUpState(
        keyguardState: KeyguardState = KeyguardState.LOCKSCREEN,
        isQuickSettingsVisible: Boolean = false,
    ) {
        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                to = keyguardState,
            ),
        )
        keyguardRepository.setQuickSettingsVisible(isVisible = isQuickSettingsVisible)
    }
}
