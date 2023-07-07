/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone

import android.app.PendingIntent
import android.content.Intent
import android.os.RemoteException
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.assist.AssistManager
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ActivityStarterImplTest : SysuiTestCase() {
    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var assistManager: AssistManager
    @Mock private lateinit var dozeServiceHost: DozeServiceHost
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var keyguardViewMediator: KeyguardViewMediator
    @Mock private lateinit var shadeController: ShadeController
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var activityLaunchAnimator: ActivityLaunchAnimator
    @Mock private lateinit var lockScreenUserManager: NotificationLockscreenUserManager
    @Mock private lateinit var statusBarWindowController: StatusBarWindowController
    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityIntentHelper: ActivityIntentHelper
    private lateinit var underTest: ActivityStarterImpl
    private val mainExecutor = FakeExecutor(FakeSystemClock())

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            ActivityStarterImpl(
                Lazy { Optional.of(centralSurfaces) },
                Lazy { assistManager },
                Lazy { dozeServiceHost },
                Lazy { biometricUnlockController },
                Lazy { keyguardViewMediator },
                Lazy { shadeController },
                Lazy { statusBarKeyguardViewManager },
                activityLaunchAnimator,
                context,
                lockScreenUserManager,
                statusBarWindowController,
                wakefulnessLifecycle,
                keyguardStateController,
                statusBarStateController,
                keyguardUpdateMonitor,
                deviceProvisionedController,
                userTracker,
                activityIntentHelper,
                mainExecutor,
            )
        whenever(userTracker.userHandle).thenReturn(UserHandle.OWNER)
    }

    @Test
    fun startPendingIntentDismissingKeyguard_keyguardShowing_dismissWithAction() {
        val pendingIntent = mock(PendingIntent::class.java)
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)

        underTest.startPendingIntentDismissingKeyguard(pendingIntent)

        verify(statusBarKeyguardViewManager)
            .dismissWithAction(any(OnDismissAction::class.java), eq(null), anyBoolean(), eq(null))
    }

    @Test
    fun startPendingIntentDismissingKeyguard_associatedView_getAnimatorController() {
        val pendingIntent = mock(PendingIntent::class.java)
        val associatedView = mock(ExpandableNotificationRow::class.java)

        underTest.startPendingIntentDismissingKeyguard(
            intent = pendingIntent,
            intentSentUiThreadCallback = null,
            associatedView = associatedView,
        )

        verify(centralSurfaces).getAnimatorControllerFromNotification(associatedView)
    }

    @Test
    fun startActivity_noUserHandleProvided_getUserHandle() {
        val intent = mock(Intent::class.java)

        underTest.startActivity(intent, false)

        verify(userTracker).userHandle
    }

    @Test
    fun postStartActivityDismissingKeyguard_pendingIntent_postsOnMain() {
        val intent = mock(PendingIntent::class.java)

        underTest.postStartActivityDismissingKeyguard(intent)

        assertThat(mainExecutor.numPending()).isEqualTo(1)
    }

    @Test
    fun postStartActivityDismissingKeyguard_intent_postsOnMain() {
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
        val intent = mock(Intent::class.java)

        underTest.postStartActivityDismissingKeyguard(intent, 0)

        assertThat(mainExecutor.numPending()).isEqualTo(1)
        mainExecutor.runAllReady()

        verify(deviceProvisionedController).isDeviceProvisioned
        verify(shadeController).runPostCollapseRunnables()
    }

    @Test
    fun postStartActivityDismissingKeyguard_intent_notDeviceProvisioned_doesNotProceed() {
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(false)
        val intent = mock(Intent::class.java)

        underTest.postStartActivityDismissingKeyguard(intent, 0)
        mainExecutor.runAllReady()

        verify(deviceProvisionedController).isDeviceProvisioned
        verify(shadeController, never()).runPostCollapseRunnables()
    }

    @Test
    fun dismissKeyguardThenExecute_startWakeAndUnlock() {
        whenever(wakefulnessLifecycle.wakefulness)
            .thenReturn(WakefulnessLifecycle.WAKEFULNESS_ASLEEP)
        whenever(keyguardStateController.canDismissLockScreen()).thenReturn(true)
        whenever(statusBarStateController.leaveOpenOnKeyguardHide()).thenReturn(false)
        whenever(dozeServiceHost.isPulsing).thenReturn(true)

        underTest.dismissKeyguardThenExecute({ true }, {}, false)

        verify(biometricUnlockController)
            .startWakeAndUnlock(BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING)
    }

    @Test
    fun dismissKeyguardThenExecute_keyguardIsShowing_dismissWithAction() {
        val customMessage = "Enter your pin."
        whenever(keyguardStateController.isShowing).thenReturn(true)

        underTest.dismissKeyguardThenExecute({ true }, {}, false, customMessage)

        verify(statusBarKeyguardViewManager)
            .dismissWithAction(
                any(OnDismissAction::class.java),
                any(Runnable::class.java),
                eq(false),
                eq(customMessage)
            )
    }

    @Test
    fun dismissKeyguardThenExecute_awakeDreams() {
        val customMessage = "Enter your pin."
        var dismissActionExecuted = false
        whenever(keyguardStateController.isShowing).thenReturn(false)
        whenever(keyguardUpdateMonitor.isDreaming).thenReturn(true)

        underTest.dismissKeyguardThenExecute(
            {
                dismissActionExecuted = true
                true
            },
            {},
            false,
            customMessage
        )

        verify(centralSurfaces).awakenDreams()
        assertThat(dismissActionExecuted).isTrue()
    }

    @Test
    @Throws(RemoteException::class)
    fun executeRunnableDismissingKeyguard_dreaming_notShowing_awakenDreams() {
        whenever(keyguardStateController.isShowing).thenReturn(false)
        whenever(keyguardStateController.isOccluded).thenReturn(false)
        whenever(keyguardUpdateMonitor.isDreaming).thenReturn(true)

        underTest.executeRunnableDismissingKeyguard(
            runnable = {},
            cancelAction = null,
            dismissShade = false,
            afterKeyguardGone = false,
            deferred = false
        )

        verify(centralSurfaces, times(1)).awakenDreams()
    }

    @Test
    @Throws(RemoteException::class)
    fun executeRunnableDismissingKeyguard_notDreaming_notShowing_doNotAwakenDreams() {
        whenever(keyguardStateController.isShowing).thenReturn(false)
        whenever(keyguardStateController.isOccluded).thenReturn(false)
        whenever(keyguardUpdateMonitor.isDreaming).thenReturn(false)

        underTest.executeRunnableDismissingKeyguard(
            runnable = {},
            cancelAction = null,
            dismissShade = false,
            afterKeyguardGone = false,
            deferred = false
        )

        verify(centralSurfaces, never()).awakenDreams()
    }

    @Test
    fun postQSRunnableDismissingKeyguard_leaveOpenStatusBarState() {
        underTest.postQSRunnableDismissingKeyguard {}

        assertThat(mainExecutor.numPending()).isEqualTo(1)
        mainExecutor.runAllReady()
        verify(statusBarStateController).setLeaveOpenOnKeyguardHide(true)
    }
}
