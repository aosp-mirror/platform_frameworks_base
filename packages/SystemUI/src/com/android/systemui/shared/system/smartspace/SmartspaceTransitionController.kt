/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shared.system.smartspace

import android.graphics.Rect
import android.view.View
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.QuickStepContract
import kotlin.math.min

/**
 * Controller that keeps track of SmartSpace instances in remote processes (such as Launcher),
 * allowing System UI to query or update their state during shared-element transitions.
 */
class SmartspaceTransitionController {

    /**
     * Implementation of [ISmartspaceTransitionController] that we provide to Launcher, allowing it
     * to provide us with a callback to query and update the state of its Smartspace.
     */
    private val ISmartspaceTransitionController = object : ISmartspaceTransitionController.Stub() {
        override fun setSmartspace(callback: ISmartspaceCallback?) {
            this@SmartspaceTransitionController.launcherSmartspace = callback
            updateLauncherSmartSpaceState()
        }
    }

    /**
     * Callback provided by Launcher to allow us to query and update the state of its SmartSpace.
     */
    public var launcherSmartspace: ISmartspaceCallback? = null

    public var lockscreenSmartspace: View? = null

    /**
     * Cached state of the Launcher SmartSpace. Retrieving the state is an IPC, so we should avoid
     * unnecessary
     */
    public var mLauncherSmartspaceState: SmartspaceState? = null

    /**
     * The bounds of our SmartSpace when the shared element transition began. We'll interpolate
     * between this and [smartspaceDestinationBounds] as the dismiss amount changes.
     */
    private val smartspaceOriginBounds = Rect()

    /** The bounds of the Launcher's SmartSpace, which is where we are animating our SmartSpace. */

    private val smartspaceDestinationBounds = Rect()

    fun createExternalInterface(): ISmartspaceTransitionController {
        return ISmartspaceTransitionController
    }

    /**
     * Updates [mLauncherSmartspaceState] and returns it. This will trigger a binder call, so use the
     * cached [mLauncherSmartspaceState] if possible.
     */
    fun updateLauncherSmartSpaceState(): SmartspaceState? {
        return launcherSmartspace?.smartspaceState.also {
            mLauncherSmartspaceState = it
        }
    }

    fun prepareForUnlockTransition() {
        updateLauncherSmartSpaceState().also { state ->
            if (state?.boundsOnScreen != null && lockscreenSmartspace != null) {
                lockscreenSmartspace!!.getBoundsOnScreen(smartspaceOriginBounds)
                with(smartspaceDestinationBounds) {
                    set(state.boundsOnScreen)
                    offset(-lockscreenSmartspace!!.paddingLeft,
                            -lockscreenSmartspace!!.paddingTop)
                }
            }
        }
    }

    fun setProgressToDestinationBounds(progress: Float) {
        if (!isSmartspaceTransitionPossible()) {
            return
        }

        val progressClamped = min(1f, progress)

        // Calculate the distance (relative to the origin) that we need to be for the current
        // progress value.
        val progressX =
                (smartspaceDestinationBounds.left - smartspaceOriginBounds.left) * progressClamped
        val progressY =
                (smartspaceDestinationBounds.top - smartspaceOriginBounds.top) * progressClamped

        val lockscreenSmartspaceCurrentBounds = Rect().also {
            lockscreenSmartspace!!.getBoundsOnScreen(it)
        }

        // Figure out how far that is from our present location on the screen. This approach
        // compensates for the fact that our parent container is also translating to animate out.
        val dx = smartspaceOriginBounds.left + progressX -
                lockscreenSmartspaceCurrentBounds.left
        var dy = smartspaceOriginBounds.top + progressY -
                lockscreenSmartspaceCurrentBounds.top

        with(lockscreenSmartspace!!) {
            translationX = translationX + dx
            translationY = translationY + dy
        }
    }

    /**
     * Whether we're capable of performing the Smartspace shared element transition when we unlock.
     * This is true if:
     *
     * - The Launcher registered a Smartspace with us, it's reporting non-empty bounds on screen.
     * - Launcher is behind the keyguard, and the Smartspace is visible on the currently selected
     *   page.
     */
    public fun isSmartspaceTransitionPossible(): Boolean {
        val smartSpaceNullOrBoundsEmpty = mLauncherSmartspaceState?.boundsOnScreen?.isEmpty ?: true
        return isLauncherUnderneath() && !smartSpaceNullOrBoundsEmpty
    }

    companion object {
        fun isLauncherUnderneath(): Boolean {
            return ActivityManagerWrapper.getInstance()
                    .runningTask?.topActivity?.className?.equals(
                            QuickStepContract.LAUNCHER_ACTIVITY_CLASS_NAME) ?: false
        }
    }
}