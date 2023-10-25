/*
 *
 *  * Copyright (C) 2023 The Android Open Source Project
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */
package com.android.systemui.keyguard.ui.view

import android.graphics.Rect
import android.util.Log
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.InWindowLauncherUnlockAnimationInteractor
import com.android.systemui.keyguard.ui.binder.InWindowLauncherAnimationViewBinder
import com.android.systemui.keyguard.ui.viewmodel.InWindowLauncherAnimationViewModel
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.shared.system.smartspace.ISysuiUnlockAnimationController
import com.android.systemui.shared.system.smartspace.SmartspaceState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

private val TAG = InWindowLauncherUnlockAnimationManager::class.simpleName
private const val UNLOCK_ANIMATION_DURATION = 633L
private const val UNLOCK_START_DELAY = 100L

/**
 * Handles interactions between System UI and Launcher related to the in-window unlock animation.
 *
 * Launcher registers its unlock controller with us here, and we use that to prepare for and start
 * the unlock animation.
 */
@SysUISingleton
class InWindowLauncherUnlockAnimationManager
@Inject
constructor(
    val interactor: InWindowLauncherUnlockAnimationInteractor,
    val viewModel: InWindowLauncherAnimationViewModel,
    @Application val scope: CoroutineScope,
) : ISysuiUnlockAnimationController.Stub() {

    /**
     * The smartspace view on the lockscreen. This is used to perform the shared element animation
     * between the lockscreen smartspace and the launcher one.
     */
    var lockscreenSmartspace: View? = null

    private var launcherAnimationController: ILauncherUnlockAnimationController? = null

    /**
     * Whether we've called [ILauncherUnlockAnimationController.prepareForUnlock], and have *not*
     * subsequently called [ILauncherUnlockAnimationController.playUnlockAnimation] or
     * [ILauncherUnlockAnimationController.setUnlockAmount].
     */
    private var preparedForUnlock = false

    /**
     * Most recent value passed to [ILauncherUnlockAnimationController.setUnlockAmount] during this
     * unlock.
     *
     * Null if we have not set a manual unlock amount, or once [ensureUnlockedOrAnimatingUnlocked]
     * has been called.
     */
    private var manualUnlockAmount: Float? = null

    /**
     * Called from [OverviewProxyService] to provide us with the launcher unlock animation
     * controller, which can be used to start and update the unlock animation in the launcher
     * process.
     */
    override fun setLauncherUnlockController(
        activityClass: String,
        launcherController: ILauncherUnlockAnimationController,
    ) {
        interactor.setLauncherActivityClass(activityClass)
        launcherAnimationController = launcherController

        // Bind once we have a launcher controller.
        InWindowLauncherAnimationViewBinder.bind(viewModel, this, scope)
    }

    /**
     * Called from the launcher process when their smartspace state updates something we should know
     * about.
     */
    override fun onLauncherSmartspaceStateUpdated(state: SmartspaceState?) {
        interactor.setLauncherSmartspaceState(state)
    }

    /**
     * Requests that the launcher prepare for unlock by becoming blank and optionally positioning
     * its smartspace at the same position as the lockscreen smartspace.
     *
     * This state is dangerous - the launcher will remain blank until we ask it to animate unlocked,
     * either via [playUnlockAnimation] or [setUnlockAmount]. If you don't want to get funny but bad
     * bugs titled "tiny launcher" or "Expected: launcher icons; Actual: no icons ever", be very
     * careful here.
     */
    fun prepareForUnlock() {
        launcherAnimationController?.let { launcher ->
            if (!preparedForUnlock) {
                preparedForUnlock = true
                manualUnlockAmount = null

                launcher.prepareForUnlock(
                    false,
                    Rect(),
                    0
                ) // TODO(b/293894758): Add smartspace animation support.
            }
        }
    }

    /** Ensures that the launcher is either fully visible, or animating to be fully visible. */
    fun ensureUnlockedOrAnimatingUnlocked() {
        val preparedButDidNotStartAnimation =
            preparedForUnlock && !interactor.startedUnlockAnimation.value
        val manualUnlockSetButNotFullyVisible =
            manualUnlockAmount != null && manualUnlockAmount != 1f

        if (preparedButDidNotStartAnimation) {
            Log.e(
                TAG,
                "Called prepareForUnlock(), but not playUnlockAnimation(). " +
                    "Failing-safe by calling setUnlockAmount(1f)"
            )
            setUnlockAmount(1f, forceIfAnimating = true)
        } else if (manualUnlockSetButNotFullyVisible) {
            Log.e(
                TAG,
                "Unlock has ended, but manual unlock amount != 1f. " +
                    "Failing-safe by calling setUnlockAmount(1f)"
            )
            setUnlockAmount(1f, forceIfAnimating = true)
        }

        manualUnlockAmount = null // Un-set the manual unlock amount as we're now visible.
    }

    /**
     * Asks launcher to play the in-window unlock animation with the specified parameters.
     *
     * Once this is called, we're no longer [preparedForUnlock] as unlock is underway.
     */
    fun playUnlockAnimation(
        unlocked: Boolean,
        duration: Long = UNLOCK_ANIMATION_DURATION,
        startDelay: Long = UNLOCK_START_DELAY,
    ) {
        if (preparedForUnlock) {
            launcherAnimationController?.let { launcher ->
                launcher.playUnlockAnimation(unlocked, duration, startDelay)
                interactor.setStartedUnlockAnimation(true)
            }
        } else {
            Log.e(TAG, "Attempted to call playUnlockAnimation() before prepareToUnlock().")
        }

        preparedForUnlock = false
    }

    /**
     * Clears the played unlock animation flag. Since we don't have access to an onAnimationEnd
     * event for the launcher animation (since it's in a different process), this is called whenever
     * the transition to GONE ends or the surface becomes unavailable. In both cases, we'd need to
     * play the animation next time we unlock.
     */
    fun clearStartedUnlockAnimation() {
        interactor.setStartedUnlockAnimation(false)
    }

    /**
     * Manually sets the unlock amount on launcher. This is used to explicitly set us to fully
     * unlocked, or to manually control the animation (such as during a swipe to unlock).
     *
     * Once this is called, we're no longer [preparedForUnlock] since the Launcher icons are not
     * configured to be invisible for the start of the unlock animation.
     */
    fun setUnlockAmount(amount: Float, forceIfAnimating: Boolean) {
        preparedForUnlock = false

        launcherAnimationController?.let {
            manualUnlockAmount = amount
            it.setUnlockAmount(amount, forceIfAnimating)
        }
    }
}
