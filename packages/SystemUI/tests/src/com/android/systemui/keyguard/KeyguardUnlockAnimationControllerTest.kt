package com.android.systemui.keyguard

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.graphics.Point
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.SyncRtSurfaceTransactionApplier
import android.view.ViewRootImpl
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardViewController
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.policy.KeyguardStateController
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor.forClass
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class KeyguardUnlockAnimationControllerTest : SysuiTestCase() {
    private lateinit var keyguardUnlockAnimationController: KeyguardUnlockAnimationController

    @Mock
    private lateinit var keyguardViewMediator: KeyguardViewMediator
    @Mock
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var keyguardViewController: KeyguardViewController
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Mock
    private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock
    private lateinit var surfaceTransactionApplier: SyncRtSurfaceTransactionApplier
    @Mock
    private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock
    private lateinit var notificationShadeWindowController: NotificationShadeWindowController

    @Mock
    private lateinit var launcherUnlockAnimationController: ILauncherUnlockAnimationController.Stub

    private lateinit var remoteAnimationTarget: RemoteAnimationTarget

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        keyguardUnlockAnimationController = KeyguardUnlockAnimationController(
            context, keyguardStateController, { keyguardViewMediator }, keyguardViewController,
            featureFlags, { biometricUnlockController }, statusBarStateController,
            notificationShadeWindowController
        )
        keyguardUnlockAnimationController.setLauncherUnlockController(
            launcherUnlockAnimationController)

        `when`(keyguardViewController.viewRootImpl).thenReturn(mock(ViewRootImpl::class.java))

        // All of these fields are final, so we can't mock them, but are needed so that the surface
        // appear amount setter doesn't short circuit.
        remoteAnimationTarget = RemoteAnimationTarget(
            0, 0, null, false, Rect(), Rect(), 0, Point(), Rect(), Rect(),
            mock(WindowConfiguration::class.java), false, mock(SurfaceControl::class.java), Rect(),
            mock(ActivityManager.RunningTaskInfo::class.java), false)

        // Set the surface applier to our mock so that we can verify the arguments passed to it.
        // This applier does not have any side effects within the unlock animation controller, so
        // this is a reasonable way to test.
        keyguardUnlockAnimationController.surfaceTransactionApplier = surfaceTransactionApplier
    }

    /**
     * If we're wake and unlocking, we are animating from the black/AOD screen to the app/launcher
     * underneath. The LightRevealScrim will animate circularly from the fingerprint reader,
     * revealing the app/launcher below. In this case, we want to make sure we are not animating the
     * surface, or the user will see the wallpaper briefly as the app animates in.
     */
    @Test
    fun noSurfaceAnimation_ifWakeAndUnlocking() {
        `when`(biometricUnlockController.isWakeAndUnlock).thenReturn(true)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTarget,
            0 /* startTime */,
            false /* requestedShowSurfaceBehindKeyguard */
        )

        val captor = forClass(SyncRtSurfaceTransactionApplier.SurfaceParams::class.java)
        verify(surfaceTransactionApplier, times(1)).scheduleApply(captor.capture())

        val params = captor.value

        // We expect that we've instantly set the surface behind to alpha = 1f, and have no
        // transforms (translate, scale) on its matrix.
        assertEquals(params.alpha, 1f)
        assertTrue(params.matrix.isIdentity)

        // Also expect we've immediately asked the keyguard view mediator to finish the remote
        // animation.
        verify(keyguardViewMediator, times(1)).onKeyguardExitRemoteAnimationFinished(
            false /* cancelled */)

        verifyNoMoreInteractions(surfaceTransactionApplier)
    }

    /**
     * If we are not wake and unlocking, we expect the unlock animation to play normally.
     */
    @Test
    fun surfaceAnimation_ifNotWakeAndUnlocking() {
        `when`(biometricUnlockController.isWakeAndUnlock).thenReturn(false)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTarget,
            0 /* startTime */,
            false /* requestedShowSurfaceBehindKeyguard */
        )

        // Since the animation is running, we should not have finished the remote animation.
        verify(keyguardViewMediator, times(0)).onKeyguardExitRemoteAnimationFinished(
            false /* cancelled */)
    }

    /**
     * If we requested that the surface behind be made visible, and we're not flinging away the
     * keyguard, it means that we're swiping to unlock and want the surface visible so it can follow
     * the user's touch event as they swipe to unlock.
     *
     * In this case, we should verify that the surface was made visible via the alpha fade in
     * animator, and verify that we did not start the canned animation to animate the surface in
     * (since it's supposed to be following the touch events).
     */
    @Test
    fun fadeInSurfaceBehind_ifRequestedShowSurface_butNotFlinging() {
        `when`(keyguardStateController.isFlingingToDismissKeyguard).thenReturn(false)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTarget,
            0 /* startTime */,
            true /* requestedShowSurfaceBehindKeyguard */
        )

        assertTrue(keyguardUnlockAnimationController.surfaceBehindAlphaAnimator.isRunning)
        assertFalse(keyguardUnlockAnimationController.isPlayingCannedUnlockAnimation())
    }

    /**
     * We requested the surface behind to be made visible, but we're now flinging to dismiss the
     * keyguard. This means this was a swipe to dismiss gesture but the user flung the keyguard and
     * lifted their finger while we were requesting the surface be made visible.
     *
     * In this case, we should verify that we are playing the canned unlock animation and not
     * simply fading in the surface.
     */
    @Test
    fun playCannedUnlockAnimation_ifRequestedShowSurface_andFlinging() {
        `when`(keyguardStateController.isFlingingToDismissKeyguard).thenReturn(true)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTarget,
            0 /* startTime */,
            true /* requestedShowSurfaceBehindKeyguard */
        )

        assertTrue(keyguardUnlockAnimationController.isPlayingCannedUnlockAnimation())
        assertFalse(keyguardUnlockAnimationController.surfaceBehindAlphaAnimator.isRunning)
    }

    /**
     * We never requested the surface behind to be made visible, which means no swiping to unlock
     * ever happened and we're just playing the simple canned animation (happens via UDFPS unlock,
     * long press on the lock icon, etc).
     *
     * In this case, we should verify that we are playing the canned unlock animation and not
     * simply fading in the surface.
     */
    @Test
    fun playCannedUnlockAnimation_ifDidNotRequestShowSurface() {
        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTarget,
            0 /* startTime */,
            false /* requestedShowSurfaceBehindKeyguard */
        )

        assertTrue(keyguardUnlockAnimationController.isPlayingCannedUnlockAnimation())
        assertFalse(keyguardUnlockAnimationController.surfaceBehindAlphaAnimator.isRunning)
    }

    @Test
    fun doNotPlayCannedUnlockAnimation_ifLaunchingApp() {
        `when`(notificationShadeWindowController.isLaunchingActivity).thenReturn(true)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTarget,
            0 /* startTime */,
            true /* requestedShowSurfaceBehindKeyguard */
        )

        assertFalse(keyguardUnlockAnimationController.canPerformInWindowLauncherAnimations())
        assertFalse(keyguardUnlockAnimationController.isPlayingCannedUnlockAnimation())
    }
}