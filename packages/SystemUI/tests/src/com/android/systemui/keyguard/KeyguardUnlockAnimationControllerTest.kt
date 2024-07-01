package com.android.systemui.keyguard

import android.app.ActivityManager
import android.app.WallpaperManager
import android.app.WindowConfiguration
import android.graphics.Point
import android.graphics.Rect
import android.os.PowerManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.SyncRtSurfaceTransactionApplier
import android.view.View
import android.view.ViewRootImpl
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardViewController
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.clearInvocations
import java.util.function.Predicate

@RunWith(AndroidJUnit4::class)
@RunWithLooper
@SmallTest
class KeyguardUnlockAnimationControllerTest : SysuiTestCase() {
    private lateinit var keyguardUnlockAnimationController: KeyguardUnlockAnimationController

    @Mock
    private lateinit var windowManager: WindowManager
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
    private lateinit var powerManager: PowerManager
    @Mock
    private lateinit var wallpaperManager: WallpaperManager

    @Mock
    private lateinit var launcherUnlockAnimationController: ILauncherUnlockAnimationController.Stub

    private var surfaceControl1 = mock(SurfaceControl::class.java)
    private var remoteTarget1 = RemoteAnimationTarget(
            0 /* taskId */, 0, surfaceControl1, false, Rect(), Rect(), 0, Point(), Rect(), Rect(),
            mock(WindowConfiguration::class.java), false, surfaceControl1, Rect(),
            mock(ActivityManager.RunningTaskInfo::class.java), false)

    private var surfaceControl2 = mock(SurfaceControl::class.java)
    private var remoteTarget2 = RemoteAnimationTarget(
            1 /* taskId */, 0, surfaceControl2, false, Rect(), Rect(), 0, Point(), Rect(), Rect(),
            mock(WindowConfiguration::class.java), false, surfaceControl2, Rect(),
            mock(ActivityManager.RunningTaskInfo::class.java), false)
    private lateinit var remoteAnimationTargets: Array<RemoteAnimationTarget>

    private var surfaceControlWp = mock(SurfaceControl::class.java)
    private var wallpaperTarget = RemoteAnimationTarget(
            2 /* taskId */, 0, surfaceControlWp, false, Rect(), Rect(), 0, Point(), Rect(), Rect(),
            mock(WindowConfiguration::class.java), false, surfaceControlWp, Rect(),
            mock(ActivityManager.RunningTaskInfo::class.java), false)
    private lateinit var wallpaperTargets: Array<RemoteAnimationTarget>

    private var surfaceControlLockWp = mock(SurfaceControl::class.java)
    private var lockWallpaperTarget = RemoteAnimationTarget(
            3 /* taskId */, 0, surfaceControlLockWp, false, Rect(), Rect(), 0, Point(), Rect(),
            Rect(), mock(WindowConfiguration::class.java), false, surfaceControlLockWp,
            Rect(), mock(ActivityManager.RunningTaskInfo::class.java), false)
    private lateinit var lockWallpaperTargets: Array<RemoteAnimationTarget>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        keyguardUnlockAnimationController = KeyguardUnlockAnimationController(
            windowManager, context.resources,
            keyguardStateController, { keyguardViewMediator }, keyguardViewController,
            featureFlags, { biometricUnlockController }, statusBarStateController,
            notificationShadeWindowController, powerManager, wallpaperManager
        )
        keyguardUnlockAnimationController.setLauncherUnlockController(
            "", launcherUnlockAnimationController)

        whenever(keyguardViewController.viewRootImpl).thenReturn(mock(ViewRootImpl::class.java))
        whenever(powerManager.isInteractive).thenReturn(true)

        // All of these fields are final, so we can't mock them, but are needed so that the surface
        // appear amount setter doesn't short circuit.
        remoteAnimationTargets = arrayOf(remoteTarget1)
        wallpaperTargets = arrayOf(wallpaperTarget)
        lockWallpaperTargets = arrayOf(lockWallpaperTarget)

        // Set the surface applier to our mock so that we can verify the arguments passed to it.
        // This applier does not have any side effects within the unlock animation controller, so
        // this is a reasonable way to test.
        keyguardUnlockAnimationController.surfaceTransactionApplier = surfaceTransactionApplier
    }

    @After
    fun tearDown() {
        keyguardUnlockAnimationController.notifyFinishedKeyguardExitAnimation(true)
    }

    /**
     * If we're wake and unlocking, we are animating from the black/AOD screen to the app/launcher
     * underneath. The LightRevealScrim will animate circularly from the fingerprint reader,
     * revealing the app/launcher below. In this case, we want to make sure we are not animating the
     * surface, or the user will see the wallpaper briefly as the app animates in.
     */
    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun noSurfaceAnimation_ifWakeAndUnlocking() {
        whenever(biometricUnlockController.isWakeAndUnlock).thenReturn(true)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTargets,
            arrayOf(),
            arrayOf(),
            0 /* startTime */,
            false /* requestedShowSurfaceBehindKeyguard */
        )

        val captorSb = ArgThatCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>()
        verify(surfaceTransactionApplier, times(1)).scheduleApply(
                captorSb.capture { sp -> sp.surface == surfaceControl1 })

        val params = captorSb.getLastValue()

        // We expect that we've instantly set the surface behind to alpha = 1f, and have no
        // transforms (translate, scale) on its matrix.
        assertEquals(1f, params.alpha)
        assertTrue(params.matrix.isIdentity)

        // Also expect we've immediately asked the keyguard view mediator to finish the remote
        // animation.
        verify(keyguardViewMediator, times(1)).exitKeyguardAndFinishSurfaceBehindRemoteAnimation(
            false /* cancelled */)

        verifyNoMoreInteractions(surfaceTransactionApplier)
    }

    /**
     * If we are not wake and unlocking, we expect the unlock animation to play normally.
     */
    @Test
    fun surfaceAnimation_ifNotWakeAndUnlocking() {
        whenever(biometricUnlockController.isWakeAndUnlock).thenReturn(false)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTargets,
            wallpaperTargets,
            arrayOf(),
            0 /* startTime */,
            false /* requestedShowSurfaceBehindKeyguard */
        )

        // Since the animation is running, we should not have finished the remote animation.
        verify(keyguardViewMediator, times(0)).exitKeyguardAndFinishSurfaceBehindRemoteAnimation(
            false /* cancelled */)
    }

    @Test
    fun onWakeAndUnlock_notifiesListenerWithTrue() {
        whenever(biometricUnlockController.isWakeAndUnlock).thenReturn(true)
        whenever(biometricUnlockController.mode).thenReturn(
            BiometricUnlockController.MODE_WAKE_AND_UNLOCK)

        val listener = mock(
            KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener::class.java)
        keyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(listener)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTargets,
            wallpaperTargets,
            arrayOf(),
            0 /* startTime */,
            false /* requestedShowSurfaceBehindKeyguard */
        )

        verify(listener).onUnlockAnimationStarted(any(), eq(true), any(), any())
    }

    @Test
    fun onWakeAndUnlockFromDream_notifiesListenerWithFalse() {
        whenever(biometricUnlockController.isWakeAndUnlock).thenReturn(true)
        whenever(biometricUnlockController.mode).thenReturn(
            BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM)

        val listener = mock(
            KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener::class.java)
        keyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(listener)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTargets,
            wallpaperTargets,
            arrayOf(),
            0 /* startTime */,
            false /* requestedShowSurfaceBehindKeyguard */
        )

        verify(listener).onUnlockAnimationStarted(any(), eq(false), any(), any())
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
        whenever(keyguardStateController.isFlingingToDismissKeyguard).thenReturn(false)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTargets,
            wallpaperTargets,
            arrayOf(),
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
        whenever(keyguardStateController.isFlingingToDismissKeyguard).thenReturn(true)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTargets,
            wallpaperTargets,
            arrayOf(),
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
            remoteAnimationTargets,
            wallpaperTargets,
            arrayOf(),
            0 /* startTime */,
            false /* requestedShowSurfaceBehindKeyguard */
        )

        assertTrue(keyguardUnlockAnimationController.isPlayingCannedUnlockAnimation())
        assertFalse(keyguardUnlockAnimationController.surfaceBehindAlphaAnimator.isRunning)
    }

    @Test
    fun doNotPlayCannedUnlockAnimation_ifLaunchingApp() {
        whenever(notificationShadeWindowController.isLaunchingActivity).thenReturn(true)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
            remoteAnimationTargets,
            wallpaperTargets,
            arrayOf(),
            0 /* startTime */,
            true /* requestedShowSurfaceBehindKeyguard */
        )

        assertFalse(keyguardUnlockAnimationController.canPerformInWindowLauncherAnimations())
        assertFalse(keyguardUnlockAnimationController.isPlayingCannedUnlockAnimation())
    }

    @Test
    fun playCannedUnlockAnimation_nullSmartspaceView_doesNotThrowExecption() {
        keyguardUnlockAnimationController.lockscreenSmartspace = null
        keyguardUnlockAnimationController.willUnlockWithInWindowLauncherAnimations = true

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
                remoteAnimationTargets,
                wallpaperTargets,
                arrayOf(),
                0 /* startTime */,
                false /* requestedShowSurfaceBehindKeyguard */
        )

        assertTrue(keyguardUnlockAnimationController.isPlayingCannedUnlockAnimation())
    }

    /**
     * The canned animation should launch a cross fade when there are different wallpapers on lock
     * and home screen.
     */
    @Test
    @EnableFlags(Flags.FLAG_FASTER_UNLOCK_TRANSITION)
    fun manualUnlock_multipleWallpapers() {
        var lastFadeInAlpha = -1f
        var lastFadeOutAlpha = -1f

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
                arrayOf(remoteTarget1, remoteTarget2),
                wallpaperTargets,
                lockWallpaperTargets,
                0 /* startTime */,
                false /* requestedShowSurfaceBehindKeyguard */
        )

        for (i in 0..10) {
            clearInvocations(surfaceTransactionApplier)
            val amount = i / 10f

            keyguardUnlockAnimationController.setSurfaceBehindAppearAmount(amount)

            val captorSb = ArgThatCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>()
            verify(surfaceTransactionApplier, times(2)).scheduleApply(
                    captorSb.capture { sp ->
                        sp.surface == surfaceControlWp || sp.surface == surfaceControlLockWp })

            val fadeInAlpha = captorSb.getLastValue { it.surface == surfaceControlWp }.alpha
            val fadeOutAlpha = captorSb.getLastValue { it.surface == surfaceControlLockWp }.alpha

            if (amount == 0f) {
                assertTrue (fadeInAlpha == 0f)
                assertTrue (fadeOutAlpha == 1f)
            } else if (amount == 1f) {
                assertTrue (fadeInAlpha == 1f)
                assertTrue (fadeOutAlpha == 0f)
            } else {
                assertTrue(fadeInAlpha >= lastFadeInAlpha)
                assertTrue(fadeOutAlpha <= lastFadeOutAlpha)
            }
            lastFadeInAlpha = fadeInAlpha
            lastFadeOutAlpha = fadeOutAlpha
        }
    }

    /**
     * If we are not wake and unlocking, we expect the unlock animation to play normally.
     */
    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun surfaceAnimation_multipleTargets() {
        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
                arrayOf(remoteTarget1, remoteTarget2),
                wallpaperTargets,
                arrayOf(),
                0 /* startTime */,
                false /* requestedShowSurfaceBehindKeyguard */
        )

        // Cancel the animator so we can verify only the setSurfaceBehind call below.
        keyguardUnlockAnimationController.surfaceBehindAlphaAnimator.end()
        clearInvocations(surfaceTransactionApplier)

        // Set appear to 50%, we'll just verify that we're not applying the identity matrix which
        // means an animation is in progress.
        keyguardUnlockAnimationController.setSurfaceBehindAppearAmount(0.5f)

        val captorSb = ArgThatCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>()
        verify(surfaceTransactionApplier, times(2)).scheduleApply(captorSb
                .capture { sp -> sp.surface == surfaceControl1 || sp.surface == surfaceControl2 })
        val captorWp = ArgThatCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>()
        verify(surfaceTransactionApplier, times(1).description(
                "WallpaperSurface was expected to receive scheduleApply once"
        )).scheduleApply(captorWp.capture { sp -> sp.surface == surfaceControlWp})

        val allParams = captorSb.getAllValues()

        val remainingTargets = mutableListOf(surfaceControl1, surfaceControl2)
        allParams.forEach { params ->
            assertTrue(!params.matrix.isIdentity)
            remainingTargets.remove(params.surface)
        }

        // Make sure we called applyParams with each of the surface controls once. The order does
        // not matter, so don't explicitly check for that.
        assertTrue(remainingTargets.isEmpty())

        // Since the animation is running, we should not have finished the remote animation.
        verify(keyguardViewMediator, times(0)).exitKeyguardAndFinishSurfaceBehindRemoteAnimation(
                false /* cancelled */)
    }

    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun surfaceBehindAlphaOverriddenTo0_ifNotInteractive() {
        whenever(powerManager.isInteractive).thenReturn(false)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
                remoteAnimationTargets,
                wallpaperTargets,
                arrayOf(),
                0 /* startTime */,
                false /* requestedShowSurfaceBehindKeyguard */
        )

        // Cancel the animator so we can verify only the setSurfaceBehind call below.
        keyguardUnlockAnimationController.surfaceBehindAlphaAnimator.end()
        clearInvocations(surfaceTransactionApplier)

        keyguardUnlockAnimationController.setSurfaceBehindAppearAmount(1f)
        keyguardUnlockAnimationController.setWallpaperAppearAmount(1f, wallpaperTargets)

        val captorSb = ArgThatCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>()
        verify(surfaceTransactionApplier, times(1)).scheduleApply(
                captorSb.capture { sp -> sp.surface == surfaceControl1})
        val captorWp = ArgThatCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>()
        verify(surfaceTransactionApplier, atLeastOnce().description("Wallpaper surface has  not " +
                "received scheduleApply")).scheduleApply(
                captorWp.capture { sp -> sp.surface == surfaceControlWp })

        val params = captorSb.getLastValue()

        // We expect that we've set the surface behind to alpha = 0f since we're not interactive.
        assertEquals(0f, params.alpha)
        assertTrue(params.matrix.isIdentity)

        verifyNoMoreInteractions(surfaceTransactionApplier)
    }

    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun surfaceBehindAlphaNotOverriddenTo0_ifInteractive() {
        whenever(powerManager.isInteractive).thenReturn(true)

        keyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
                remoteAnimationTargets,
                wallpaperTargets,
                arrayOf(),
                0 /* startTime */,
                false /* requestedShowSurfaceBehindKeyguard */
        )

        // Stop the animator - we just want to test whether the override is not applied.
        keyguardUnlockAnimationController.surfaceBehindAlphaAnimator.end()
        clearInvocations(surfaceTransactionApplier)

        keyguardUnlockAnimationController.setSurfaceBehindAppearAmount(1f)
        keyguardUnlockAnimationController.setWallpaperAppearAmount(1f, wallpaperTargets)

        val captorSb = ArgThatCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>()
        verify(surfaceTransactionApplier, times(1)).scheduleApply(
                captorSb.capture { sp -> sp.surface == surfaceControl1 })
        val captorWp = ArgThatCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>()
        verify(surfaceTransactionApplier, atLeastOnce().description("Wallpaper surface has  not " +
                "received scheduleApply")).scheduleApply(
                captorWp.capture { sp -> sp.surface == surfaceControlWp })

        val params = captorSb.getLastValue()
        assertEquals(1f, params.alpha)
        assertTrue(params.matrix.isIdentity)
        assertEquals("Wallpaper surface was expected to have opacity 1",
                1f, captorWp.getLastValue().alpha)

        verifyNoMoreInteractions(surfaceTransactionApplier)
    }

    @Test
    fun unlockToLauncherWithInWindowAnimations_ssViewIsVisible() {
        val mockLockscreenSmartspaceView = mock(View::class.java)
        whenever(mockLockscreenSmartspaceView.visibility).thenReturn(View.VISIBLE)
        keyguardUnlockAnimationController.lockscreenSmartspace = mockLockscreenSmartspaceView

        keyguardUnlockAnimationController.unlockToLauncherWithInWindowAnimations()

        verify(mockLockscreenSmartspaceView).visibility = View.INVISIBLE
    }

    @Test
    fun unlockToLauncherWithInWindowAnimations_ssViewIsInvisible() {
        val mockLockscreenSmartspaceView = mock(View::class.java)
        whenever(mockLockscreenSmartspaceView.visibility).thenReturn(View.INVISIBLE)
        keyguardUnlockAnimationController.lockscreenSmartspace = mockLockscreenSmartspaceView

        keyguardUnlockAnimationController.unlockToLauncherWithInWindowAnimations()

        verify(mockLockscreenSmartspaceView, never()).visibility = View.INVISIBLE
    }

    @Test
    fun unlockToLauncherWithInWindowAnimations_ssViewIsGone() {
        val mockLockscreenSmartspaceView = mock(View::class.java)
        whenever(mockLockscreenSmartspaceView.visibility).thenReturn(View.GONE)
        keyguardUnlockAnimationController.lockscreenSmartspace = mockLockscreenSmartspaceView

        keyguardUnlockAnimationController.unlockToLauncherWithInWindowAnimations()

        verify(mockLockscreenSmartspaceView, never()).visibility = View.INVISIBLE
    }

    @Test
    fun notifyFinishedKeyguardExitAnimation_ssViewIsInvisibleAndCancelledIsTrue() {
        val mockLockscreenSmartspaceView = mock(View::class.java)
        whenever(mockLockscreenSmartspaceView.visibility).thenReturn(View.INVISIBLE)
        keyguardUnlockAnimationController.lockscreenSmartspace = mockLockscreenSmartspaceView

        keyguardUnlockAnimationController.notifyFinishedKeyguardExitAnimation(true)

        verify(mockLockscreenSmartspaceView).visibility = View.VISIBLE
    }

    @Test
    fun notifyFinishedKeyguardExitAnimation_ssViewIsGoneAndCancelledIsTrue() {
        val mockLockscreenSmartspaceView = mock(View::class.java)
        whenever(mockLockscreenSmartspaceView.visibility).thenReturn(View.GONE)
        keyguardUnlockAnimationController.lockscreenSmartspace = mockLockscreenSmartspaceView

        keyguardUnlockAnimationController.notifyFinishedKeyguardExitAnimation(true)

        verify(mockLockscreenSmartspaceView, never()).visibility = View.VISIBLE
    }

    @Test
    fun notifyFinishedKeyguardExitAnimation_ssViewIsInvisibleAndCancelledIsFalse() {
        val mockLockscreenSmartspaceView = mock(View::class.java)
        whenever(mockLockscreenSmartspaceView.visibility).thenReturn(View.INVISIBLE)
        keyguardUnlockAnimationController.lockscreenSmartspace = mockLockscreenSmartspaceView

        keyguardUnlockAnimationController.notifyFinishedKeyguardExitAnimation(false)

        verify(mockLockscreenSmartspaceView).visibility = View.VISIBLE
    }

    @Test
    fun notifyFinishedKeyguardExitAnimation_ssViewIsGoneAndCancelledIsFalse() {
        val mockLockscreenSmartspaceView = mock(View::class.java)
        whenever(mockLockscreenSmartspaceView.visibility).thenReturn(View.GONE)
        keyguardUnlockAnimationController.lockscreenSmartspace = mockLockscreenSmartspaceView

        keyguardUnlockAnimationController.notifyFinishedKeyguardExitAnimation(false)

        verify(mockLockscreenSmartspaceView, never()).visibility = View.VISIBLE
    }

    private class ArgThatCaptor<T> {
        private var allArgs: MutableList<T> = mutableListOf()

        fun capture(predicate: Predicate<T>): T {
            return argThat{x: T ->
                if (predicate.test(x)) {
                    allArgs.add(x)
                    return@argThat true
                }
                return@argThat false
            }
        }

        fun getLastValue(predicate: Predicate<T>? = null): T {
            return if (predicate != null) allArgs.last(predicate::test) else allArgs.last()
        }

        fun getAllValues(): List<T> {
            return allArgs
        }
    }
}
