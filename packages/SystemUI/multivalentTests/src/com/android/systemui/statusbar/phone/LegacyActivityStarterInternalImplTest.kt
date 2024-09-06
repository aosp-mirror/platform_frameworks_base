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

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.os.UserHandle
import android.view.View
import android.widget.FrameLayout
import android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.LaunchableView
import com.android.systemui.assist.AssistManager
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.data.repository.ShadeAnimationRepository
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorLegacyImpl
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class LegacyActivityStarterInternalImplTest : SysuiTestCase() {
    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var assistManager: AssistManager
    @Mock private lateinit var dozeServiceHost: DozeServiceHost
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var keyguardViewMediator: KeyguardViewMediator
    @Mock private lateinit var shadeController: ShadeController
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var activityTransitionAnimator: ActivityTransitionAnimator
    @Mock private lateinit var lockScreenUserManager: NotificationLockscreenUserManager
    @Mock private lateinit var statusBarWindowController: StatusBarWindowController
    @Mock private lateinit var notifShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityIntentHelper: ActivityIntentHelper
    @Mock private lateinit var communalSceneInteractor: CommunalSceneInteractor
    private lateinit var underTest: LegacyActivityStarterInternalImpl
    private val mainExecutor = FakeExecutor(FakeSystemClock())
    private val shadeAnimationInteractor =
        ShadeAnimationInteractorLegacyImpl(ShadeAnimationRepository(), FakeShadeRepository())

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            LegacyActivityStarterInternalImpl(
                centralSurfacesOptLazy = { Optional.of(centralSurfaces) },
                assistManagerLazy = { assistManager },
                dozeServiceHostLazy = { dozeServiceHost },
                biometricUnlockControllerLazy = { biometricUnlockController },
                keyguardViewMediatorLazy = { keyguardViewMediator },
                shadeControllerLazy = { shadeController },
                commandQueue = commandQueue,
                shadeAnimationInteractor = shadeAnimationInteractor,
                statusBarKeyguardViewManagerLazy = { statusBarKeyguardViewManager },
                notifShadeWindowControllerLazy = { notifShadeWindowController },
                activityTransitionAnimator = activityTransitionAnimator,
                context = context,
                displayId = DISPLAY_ID,
                lockScreenUserManager = lockScreenUserManager,
                statusBarWindowController = statusBarWindowController,
                wakefulnessLifecycle = wakefulnessLifecycle,
                keyguardStateController = keyguardStateController,
                statusBarStateController = statusBarStateController,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                deviceProvisionedController = deviceProvisionedController,
                userTracker = userTracker,
                activityIntentHelper = activityIntentHelper,
                mainExecutor = mainExecutor,
                communalSceneInteractor = communalSceneInteractor,
            )
        whenever(userTracker.userHandle).thenReturn(UserHandle.OWNER)
    }

    @Test
    fun startPendingIntentDismissingKeyguard_keyguardShowing_dismissWithAction() {
        val pendingIntent = mock(PendingIntent::class.java)
        whenever(pendingIntent.isActivity).thenReturn(true)
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)

        underTest.startPendingIntentDismissingKeyguard(intent = pendingIntent, dismissShade = true)
        mainExecutor.runAllReady()

        verify(statusBarKeyguardViewManager)
            .dismissWithAction(any(OnDismissAction::class.java), eq(null), anyBoolean(), eq(null))
    }

    @Test
    fun startPendingIntentMaybeDismissingKeyguard_keyguardShowing_showOverLs_launchAnimator() {
        val pendingIntent = mock(PendingIntent::class.java)
        val parent = FrameLayout(context)
        val view =
            object : View(context), LaunchableView {
                override fun setShouldBlockVisibilityChanges(block: Boolean) {}
            }
        parent.addView(view)
        val controller = ActivityTransitionAnimator.Controller.fromView(view)
        whenever(pendingIntent.isActivity).thenReturn(true)
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
        whenever(activityIntentHelper.wouldPendingShowOverLockscreen(eq(pendingIntent), anyInt()))
            .thenReturn(true)

        startPendingIntentMaybeDismissingKeyguard(
            intent = pendingIntent,
            animationController = controller,
            intentSentUiThreadCallback = null,
        )
        mainExecutor.runAllReady()

        verify(activityTransitionAnimator)
            .startPendingIntentWithAnimation(
                nullable(),
                eq(true),
                nullable(),
                eq(true),
                any(),
            )
    }

    fun startPendingIntentDismissingKeyguard_fillInIntentAndExtraOptions_sendAndReturnResult() {
        val pendingIntent = mock(PendingIntent::class.java)
        val fillInIntent = mock(Intent::class.java)
        val parent = FrameLayout(context)
        val view =
            object : View(context), LaunchableView {
                override fun setShouldBlockVisibilityChanges(block: Boolean) {}
            }
        parent.addView(view)
        val controller = ActivityTransitionAnimator.Controller.fromView(view)
        whenever(pendingIntent.isActivity).thenReturn(true)
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)
        whenever(activityIntentHelper.wouldPendingShowOverLockscreen(eq(pendingIntent), anyInt()))
            .thenReturn(false)

        // extra activity options to set on pending intent
        val activityOptions = mock(ActivityOptions::class.java)
        activityOptions.splashScreenStyle = SPLASH_SCREEN_STYLE_SOLID_COLOR
        activityOptions.isPendingIntentBackgroundActivityLaunchAllowedByPermission = false
        val bundleCaptor = argumentCaptor<Bundle>()

        startPendingIntentMaybeDismissingKeyguard(
            intent = pendingIntent,
            animationController = controller,
            intentSentUiThreadCallback = null,
            fillInIntent = fillInIntent,
            extraOptions = activityOptions.toBundle(),
        )
        mainExecutor.runAllReady()

        // Fill-in intent is passed and options contain extra values specified
        verify(pendingIntent)
            .sendAndReturnResult(
                eq(context),
                eq(0),
                eq(fillInIntent),
                nullable(),
                nullable(),
                nullable(),
                bundleCaptor.capture()
            )
        val options = ActivityOptions.fromBundle(bundleCaptor.value)
        assertThat(options.isPendingIntentBackgroundActivityLaunchAllowedByPermission).isFalse()
        assertThat(options.splashScreenStyle).isEqualTo(SPLASH_SCREEN_STYLE_SOLID_COLOR)
    }

    @Test
    fun startPendingIntentDismissingKeyguard_associatedView_getAnimatorController() {
        val pendingIntent = mock(PendingIntent::class.java)
        val associatedView = mock(ExpandableNotificationRow::class.java)

        underTest.startPendingIntentDismissingKeyguard(
            intent = pendingIntent,
            dismissShade = true,
            intentSentUiThreadCallback = null,
            associatedView = associatedView,
        )

        verify(centralSurfaces).getAnimatorControllerFromNotification(associatedView)
    }

    @Test
    fun startActivity_noUserHandleProvided_getUserHandle() {
        val intent = mock(Intent::class.java)

        underTest.startActivity(intent, false, null, false, null)

        verify(userTracker).userHandle
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
            .startWakeAndUnlock(BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING, null)
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

    private fun startPendingIntentMaybeDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
        animationController: ActivityTransitionAnimator.Controller?,
        fillInIntent: Intent? = null,
        extraOptions: Bundle? = null,
    ) {
        underTest.startPendingIntentDismissingKeyguard(
            intent = intent,
            dismissShade = true,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            animationController = animationController,
            showOverLockscreen = true,
            fillInIntent = fillInIntent,
            extraOptions = extraOptions,
        )
    }

    private companion object {
        private const val DISPLAY_ID = 0
    }
}
