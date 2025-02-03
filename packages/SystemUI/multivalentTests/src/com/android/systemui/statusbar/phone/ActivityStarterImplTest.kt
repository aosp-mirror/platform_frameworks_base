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

package com.android.systemui.statusbar.phone

import android.app.PendingIntent
import android.content.Intent
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.Flags as SharedFlags
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never

@SmallTest
@RunWith(AndroidJUnit4::class)
class ActivityStarterImplTest : SysuiTestCase() {
    @Mock private lateinit var legacyActivityStarterInternal: LegacyActivityStarterInternalImpl
    @Mock private lateinit var activityStarterInternal: ActivityStarterInternalImpl
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    private lateinit var underTest: ActivityStarterImpl
    private val kosmos = testKosmos()
    private val mainExecutor = FakeExecutor(FakeSystemClock())

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            ActivityStarterImpl(
                statusBarStateController = statusBarStateController,
                mainExecutor = mainExecutor,
                legacyActivityStarter = { legacyActivityStarterInternal },
                activityStarterInternal = { activityStarterInternal },
            )
    }

    @EnableFlags(
        SharedFlags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        SharedFlags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @EnableSceneContainer
    @Test
    fun registerTransition_forwardsTheRequest() {
        with(kosmos) {
            testScope.runTest {
                val cookie = mock(ActivityTransitionAnimator.TransitionCookie::class.java)
                val controllerFactory =
                    mock(ActivityTransitionAnimator.ControllerFactory::class.java)

                underTest.registerTransition(cookie, controllerFactory, testScope)

                verify(activityStarterInternal)
                    .registerTransition(eq(cookie), eq(controllerFactory), eq(testScope))
            }
        }
    }

    @DisableFlags(
        SharedFlags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        SharedFlags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun registerTransition_doesNotForwardTheRequest_whenFlaggedOff() {
        with(kosmos) {
            testScope.runTest {
                val cookie = mock(ActivityTransitionAnimator.TransitionCookie::class.java)
                val controllerFactory =
                    mock(ActivityTransitionAnimator.ControllerFactory::class.java)

                underTest.registerTransition(cookie, controllerFactory, testScope)

                verify(activityStarterInternal, never()).registerTransition(any(), any(), any())
            }
        }
    }

    @EnableFlags(
        SharedFlags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        SharedFlags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @EnableSceneContainer
    @Test
    fun unregisterTransition_forwardsTheRequest() {
        val cookie = mock(ActivityTransitionAnimator.TransitionCookie::class.java)

        underTest.unregisterTransition(cookie)

        verify(activityStarterInternal).unregisterTransition(eq(cookie))
    }

    @DisableFlags(
        SharedFlags.FLAG_RETURN_ANIMATION_FRAMEWORK_LIBRARY,
        SharedFlags.FLAG_RETURN_ANIMATION_FRAMEWORK_LONG_LIVED,
    )
    @Test
    fun unregisterTransition_doesNotForwardTheRequest_whenFlaggedOff() {
        val cookie = mock(ActivityTransitionAnimator.TransitionCookie::class.java)

        underTest.unregisterTransition(cookie)

        verify(activityStarterInternal, never()).unregisterTransition(any())
    }

    @Test
    fun postStartActivityDismissingKeyguard_pendingIntent_postsOnMain() {
        val intent = mock(PendingIntent::class.java)

        underTest.postStartActivityDismissingKeyguard(intent)

        assertThat(mainExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun postStartActivityDismissingKeyguard_intent_postsOnMain() {
        underTest.postStartActivityDismissingKeyguard(mock(Intent::class.java), 0)

        assertThat(mainExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun postQSRunnableDismissingKeyguard_leaveOpenStatusBarState() {
        underTest.postQSRunnableDismissingKeyguard {}

        assertThat(mainExecutor.numPending()).isEqualTo(1)
        mainExecutor.runAllReady()
        verify(statusBarStateController).setLeaveOpenOnKeyguardHide(true)
    }
}
