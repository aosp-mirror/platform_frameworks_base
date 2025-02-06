/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard

import android.app.IActivityTaskManager
import android.internal.statusbar.statusBarService
import android.os.Bundle
import android.os.PowerManager
import android.os.powerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLogger
import com.android.internal.widget.lockPatternUtils
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.keyguardUnlockAnimationController
import com.android.keyguard.mediator.ScreenOnCoordinator
import com.android.keyguard.trustManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.activityTransitionAnimator
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.ui.viewmodel.communalTransitionViewModel
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.dreams.DreamOverlayStateController
import com.android.systemui.dreams.ui.viewmodel.dreamViewModel
import com.android.systemui.dump.dumpManager
import com.android.systemui.flags.featureFlagsClassic
import com.android.systemui.flags.systemPropertiesHelper
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionBootInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.log.sessionTracker
import com.android.systemui.navigationbar.navigationModeController
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.process.processWrapper
import com.android.systemui.settings.userTracker
import com.android.systemui.shade.shadeController
import com.android.systemui.statusbar.notificationShadeDepthController
import com.android.systemui.statusbar.notificationShadeWindowController
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.statusbar.phone.scrimController
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.statusbar.policy.userSwitcherController
import com.android.systemui.testKosmos
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.settings.fakeSettings
import com.android.systemui.util.time.systemClock
import com.android.systemui.wallpapers.data.repository.wallpaperRepository
import com.android.wm.shell.keyguard.KeyguardTransitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** Kotlin version of KeyguardViewMediatorTest to allow for coroutine testing. */
@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class KeyguardViewMediatorTestKt : SysuiTestCase() {
    private val kosmos =
        testKosmos().useUnconfinedTestDispatcher().also {
            it.powerManager =
                mock<PowerManager> {
                    on { newWakeLock(anyInt(), any()) } doReturn mock<PowerManager.WakeLock>()
                }
        }

    private lateinit var testableLooper: TestableLooper

    private val Kosmos.underTest by
        Kosmos.Fixture {
            KeyguardViewMediator(
                mContext,
                uiEventLogger,
                sessionTracker,
                userTracker,
                falsingCollector,
                lockPatternUtils,
                broadcastDispatcher,
                { statusBarKeyguardViewManager },
                dismissCallbackRegistry,
                mock<KeyguardUpdateMonitor>(),
                dumpManager,
                fakeExecutor,
                powerManager,
                trustManager,
                userSwitcherController,
                DeviceConfigProxy(),
                navigationModeController,
                keyguardDisplayManager,
                dozeParameters,
                statusBarStateController,
                keyguardStateController,
                { keyguardUnlockAnimationController },
                screenOffAnimationController,
                { notificationShadeDepthController },
                mock<ScreenOnCoordinator>(),
                mock<KeyguardTransitions>(),
                interactionJankMonitor,
                mock<DreamOverlayStateController>(),
                JavaAdapter(backgroundScope),
                wallpaperRepository,
                { shadeController },
                { notificationShadeWindowController },
                { activityTransitionAnimator },
                { scrimController },
                mock<IActivityTaskManager>(),
                statusBarService,
                featureFlagsClassic,
                fakeSettings,
                fakeSettings,
                systemClock,
                processWrapper,
                testDispatcher,
                { dreamViewModel },
                { communalTransitionViewModel },
                systemPropertiesHelper,
                { mock<WindowManagerLockscreenVisibilityManager>() },
                selectedUserInteractor,
                keyguardInteractor,
                keyguardTransitionBootInteractor,
                { communalSceneInteractor },
                mock<WindowManagerOcclusionManager>(),
            )
        }

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
    }

    @Test
    fun doKeyguardTimeout_changesCommunalScene() =
        kosmos.runTest {
            // doKeyguardTimeout message received.
            val timeoutOptions = Bundle()
            timeoutOptions.putBoolean(KeyguardViewMediator.EXTRA_TRIGGER_HUB, true)
            underTest.doKeyguardTimeout(timeoutOptions)
            testableLooper.processAllMessages()

            // Hub scene is triggered.
            assertThat(communalSceneRepository.currentScene.value)
                .isEqualTo(CommunalScenes.Communal)
        }
}
