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

package com.android.systemui.keyguard

import android.app.IActivityTaskManager
import android.util.Log
import android.view.IRemoteAnimationFinishedCallback
import android.view.RemoteAnimationTarget
import android.view.WindowManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissTransitionInteractor
import com.android.systemui.keyguard.ui.binder.KeyguardSurfaceBehindParamsApplier
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Manages lockscreen and AOD visibility state via the [IActivityTaskManager], and keeps track of
 * remote animations related to changes in lockscreen visibility.
 */
@SysUISingleton
class WindowManagerLockscreenVisibilityManager
@Inject
constructor(
    @Main private val executor: Executor,
    private val activityTaskManagerService: IActivityTaskManager,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardSurfaceBehindAnimator: KeyguardSurfaceBehindParamsApplier,
    private val keyguardDismissTransitionInteractor: KeyguardDismissTransitionInteractor,
) {

    /**
     * Whether the lockscreen is showing, which we pass to [IActivityTaskManager.setLockScreenShown]
     * in order to show the lockscreen and hide the surface behind the keyguard (or the inverse).
     *
     * This value is null if we have not yet called setLockScreenShown with any value. This will
     * happen during the boot sequence, but we can't default to true here since otherwise we'll
     * short-circuit on the first call to setLockScreenShown since we'll think we're already
     * showing.
     */
    private var isLockscreenShowing: Boolean? = null

    /**
     * Whether AOD is showing, which we pass to [IActivityTaskManager.setLockScreenShown] in order
     * to show AOD when the lockscreen is visible.
     */
    private var isAodVisible = false

    /**
     * Whether the keyguard is currently "going away", which we triggered via a call to
     * [IActivityTaskManager.keyguardGoingAway]. When we tell WM that the keyguard is going away,
     * the app/launcher surface behind the keyguard is made visible, and WM calls
     * [onKeyguardGoingAwayRemoteAnimationStart] with a RemoteAnimationTarget so that we can animate
     * it.
     *
     * Going away does not inherently result in [isLockscreenShowing] being set to false; we need to
     * do that ourselves once we are done animating the surface.
     *
     * THIS IS THE ONLY PLACE 'GOING AWAY' TERMINOLOGY SHOULD BE USED. 'Going away' is a WM concept
     * and we have gotten into trouble using it to mean various different things in the past. Unlock
     * animations may still be visible when the keyguard is NOT 'going away', for example, when we
     * play in-window animations, we set the surface to alpha=1f and end the animation immediately.
     * The remainder of the animation occurs in-window, so while you might expect that the keyguard
     * is still 'going away' because unlock animations are playing, it's actually not.
     *
     * If you want to know if the keyguard is 'going away', you probably want to check if we have
     * STARTED but not FINISHED a transition to GONE.
     *
     * The going away animation will run until:
     * - We manually call [endKeyguardGoingAwayAnimation] after we're done animating.
     * - We call [setLockscreenShown] = true, which cancels the going away animation.
     * - WM calls [onKeyguardGoingAwayRemoteAnimationCancelled] for another reason (such as the 10
     *   second timeout).
     */
    private var isKeyguardGoingAway = false
        private set(value) {
            // TODO(b/278086361): Extricate the keyguard state controller.
            keyguardStateController.notifyKeyguardGoingAway(value)
            field = value
        }

    /** Callback provided by WM to call once we're done with the going away animation. */
    private var goingAwayRemoteAnimationFinishedCallback: IRemoteAnimationFinishedCallback? = null

    /**
     * Set the visibility of the surface behind the keyguard, making the appropriate calls to Window
     * Manager to effect the change.
     */
    fun setSurfaceBehindVisibility(visible: Boolean) {
        if (isKeyguardGoingAway == visible) {
            Log.d(TAG, "WmLockscreenVisibilityManager#setVisibility -> already visible=$visible")
            return
        }

        // The surface behind is always visible if the lockscreen is not showing, so we're already
        // visible.
        if (visible && isLockscreenShowing != true) {
            Log.d(TAG, "#setVisibility -> already visible since the lockscreen isn't showing")
            return
        }

        if (visible) {
            // Make the surface visible behind the keyguard by calling keyguardGoingAway. The
            // lockscreen is still showing as well, allowing us to animate unlocked.
            Log.d(TAG, "ActivityTaskManagerService#keyguardGoingAway()")
            activityTaskManagerService.keyguardGoingAway(0)
            isKeyguardGoingAway = true
        } else {
            // Hide the surface by setting the lockscreen showing.
            setLockscreenShown(true)
        }
    }

    fun setAodVisible(aodVisible: Boolean) {
        setWmLockscreenState(aodVisible = aodVisible)
    }

    /** Sets the visibility of the lockscreen. */
    fun setLockscreenShown(lockscreenShown: Boolean) {
        setWmLockscreenState(lockscreenShowing = lockscreenShown)
    }

    fun onKeyguardGoingAwayRemoteAnimationStart(
        @WindowManager.TransitionOldType transit: Int,
        apps: Array<RemoteAnimationTarget>,
        wallpapers: Array<RemoteAnimationTarget>,
        nonApps: Array<RemoteAnimationTarget>,
        finishedCallback: IRemoteAnimationFinishedCallback
    ) {
        // Ensure that we've started a dismiss keyguard transition. WindowManager can start the
        // going away animation on its own, if an activity launches and then requests dismissing the
        // keyguard. In this case, this is the first and only signal we'll receive to start
        // a transition to GONE. This transition needs to start even if we're not provided an app
        // animation target - it's possible the app is destroyed on creation, etc. but we'll still
        // be unlocking.
        keyguardDismissTransitionInteractor.startDismissKeyguardTransition(
            reason = "Going away remote animation started"
        )

        if (apps.isNotEmpty()) {
            goingAwayRemoteAnimationFinishedCallback = finishedCallback
            keyguardSurfaceBehindAnimator.applyParamsToSurface(apps[0])
        } else {
            // Nothing to do here if we have no apps, end the animation, which will cancel it and WM
            // will make *something* visible.
            finishedCallback.onAnimationFinished()
        }
    }

    fun onKeyguardGoingAwayRemoteAnimationCancelled() {
        // If WM cancelled the animation, we need to end immediately even if we're still using the
        // animation.
        endKeyguardGoingAwayAnimation()
    }

    /**
     * Whether the going away remote animation target is in-use, which means we're animating it or
     * intend to animate it.
     *
     * Some unlock animations (such as the translation spring animation) are non-deterministic and
     * might end after the transition to GONE ends. In that case, we want to keep the remote
     * animation running until the spring ends.
     */
    fun setUsingGoingAwayRemoteAnimation(usingTarget: Boolean) {
        if (!usingTarget) {
            endKeyguardGoingAwayAnimation()
        }
    }

    /**
     * Sets the lockscreen state WM-side by calling ATMS#setLockScreenShown.
     *
     * If [lockscreenShowing] is null, it means we don't know if the lockscreen is showing yet. This
     * will be decided by the [KeyguardTransitionBootInteractor] shortly.
     */
    private fun setWmLockscreenState(
        lockscreenShowing: Boolean? = this.isLockscreenShowing,
        aodVisible: Boolean = this.isAodVisible
    ) {
        Log.d(
            TAG,
            "#setWmLockscreenState(" +
                "isLockscreenShowing=$lockscreenShowing, " +
                "aodVisible=$aodVisible)."
        )

        if (lockscreenShowing == null) {
            Log.d(
                TAG,
                "isAodVisible=$aodVisible, but lockscreenShowing=null. Waiting for" +
                    "non-null lockscreenShowing before calling ATMS#setLockScreenShown, which" +
                    "will happen once KeyguardTransitionBootInteractor starts the boot transition."
            )
            this.isAodVisible = aodVisible
            return
        }

        if (this.isLockscreenShowing == lockscreenShowing && this.isAodVisible == aodVisible) {
            return
        }

        Log.d(
            TAG,
            "ATMS#setLockScreenShown(" +
                "isLockscreenShowing=$lockscreenShowing, " +
                "aodVisible=$aodVisible)."
        )
        activityTaskManagerService.setLockScreenShown(lockscreenShowing, aodVisible)
        this.isLockscreenShowing = lockscreenShowing
        this.isAodVisible = aodVisible
    }

    private fun endKeyguardGoingAwayAnimation() {
        if (!isKeyguardGoingAway) {
            Log.d(
                TAG,
                "#endKeyguardGoingAwayAnimation() called when isKeyguardGoingAway=false. " +
                    "Short-circuiting."
            )
            return
        }

        executor.execute {
            Log.d(TAG, "Finishing remote animation.")
            goingAwayRemoteAnimationFinishedCallback?.onAnimationFinished()
            goingAwayRemoteAnimationFinishedCallback = null

            isKeyguardGoingAway = false

            keyguardSurfaceBehindAnimator.notifySurfaceReleased()
        }
    }

    companion object {
        private val TAG = WindowManagerLockscreenVisibilityManager::class.java.simpleName
    }
}
