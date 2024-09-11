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

package com.android.systemui.keyguard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.os.RemoteException
import android.util.Log
import android.view.IRemoteAnimationFinishedCallback
import android.view.IRemoteAnimationRunner
import android.view.RemoteAnimationTarget
import android.view.SyncRtSurfaceTransactionApplier
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
import androidx.annotation.VisibleForTesting
import com.android.app.animation.Interpolators
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.policy.ScreenDecorationsUtils
import com.android.keyguard.KeyguardViewController
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.TransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardOcclusionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import java.util.concurrent.Executor
import javax.inject.Inject

private val UNOCCLUDE_ANIMATION_DURATION = 250
private val UNOCCLUDE_TRANSLATE_DISTANCE_PERCENT = 0.1f

/**
 * Keeps track of Window Manager's occlusion state and RemoteAnimations related to changes in
 * occlusion state. Occlusion is when a [FLAG_SHOW_WHEN_LOCKED] activity is displaying over the
 * lockscreen - we're still locked, but the user can interact with the activity.
 *
 * Typical "occlusion" use cases include launching the camera over the lockscreen, tapping a quick
 * affordance to bring up Google Pay/Wallet/whatever it's called by the time you're reading this,
 * and Maps Navigation.
 *
 * Window Manager considers the keyguard to be 'occluded' whenever a [FLAG_SHOW_WHEN_LOCKED]
 * activity is on top of the task stack, even if the device is unlocked and the keyguard is not
 * visible. System UI considers the keyguard to be [KeyguardState.OCCLUDED] only when we're on the
 * keyguard and an activity is displaying over it.
 *
 * For all System UI use cases, you should use [KeyguardTransitionInteractor] to determine if we're
 * in the [KeyguardState.OCCLUDED] state and react accordingly. If you are sure that you need to
 * check whether Window Manager considers OCCLUDED=true even though the lockscreen is not showing,
 * use [KeyguardShowWhenLockedActivityInteractor.isShowWhenLockedActivityOnTop] in combination with
 * [KeyguardTransitionInteractor] state.
 *
 * This is a very sensitive piece of state that has caused many headaches in the past. Please be
 * careful.
 */
@SysUISingleton
class WindowManagerOcclusionManager
@Inject
constructor(
    val keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    val activityTransitionAnimator: ActivityTransitionAnimator,
    val keyguardViewController: dagger.Lazy<KeyguardViewController>,
    val powerInteractor: PowerInteractor,
    val context: Context,
    val interactionJankMonitor: InteractionJankMonitor,
    @Main executor: Executor,
    val dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    val occlusionInteractor: KeyguardOcclusionInteractor,
) {
    val powerButtonY =
        context.resources.getDimensionPixelSize(
            R.dimen.physical_power_button_center_screen_location_y
        )
    val windowCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context)

    var occludeAnimationFinishedCallback: IRemoteAnimationFinishedCallback? = null

    /**
     * Animation runner provided to WindowManager, which will be used if an occluding activity is
     * launched and Window Manager wants us to animate it in. This is used as a signal that we are
     * now occluded, and should update our state accordingly.
     */
    val occludeAnimationRunner: IRemoteAnimationRunner =
        object : IRemoteAnimationRunner.Stub() {
            override fun onAnimationStart(
                transit: Int,
                apps: Array<RemoteAnimationTarget>,
                wallpapers: Array<RemoteAnimationTarget>,
                nonApps: Array<RemoteAnimationTarget>,
                finishedCallback: IRemoteAnimationFinishedCallback?
            ) {
                Log.d(TAG, "occludeAnimationRunner#onAnimationStart")
                // Wrap the callback so that it's guaranteed to be nulled out once called.
                occludeAnimationFinishedCallback =
                    object : IRemoteAnimationFinishedCallback.Stub() {
                        override fun onAnimationFinished() {
                            finishedCallback?.onAnimationFinished()
                            occludeAnimationFinishedCallback = null
                        }
                    }
                keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
                    showWhenLockedActivityOnTop = true,
                    taskInfo = apps.firstOrNull()?.taskInfo,
                )
                activityTransitionAnimator
                    .createRunner(occludeAnimationController)
                    .onAnimationStart(
                        transit,
                        apps,
                        wallpapers,
                        nonApps,
                        occludeAnimationFinishedCallback,
                    )
            }

            override fun onAnimationCancelled() {
                Log.d(TAG, "occludeAnimationRunner#onAnimationCancelled")
            }
        }

    var unoccludeAnimationFinishedCallback: IRemoteAnimationFinishedCallback? = null

    /**
     * Animation runner provided to WindowManager, which will be used if an occluding activity is
     * finished and Window Manager wants us to animate it out. This is used as a signal that we are
     * no longer occluded, and should update our state accordingly.
     *
     * TODO(b/326464548): Restore dream specific animation.
     */
    val unoccludeAnimationRunner: IRemoteAnimationRunner =
        object : IRemoteAnimationRunner.Stub() {
            var unoccludeAnimator: ValueAnimator? = null
            val unoccludeMatrix = Matrix()

            /** TODO(b/326470033): Extract this logic into ViewModels. */
            override fun onAnimationStart(
                transit: Int,
                apps: Array<RemoteAnimationTarget>,
                wallpapers: Array<RemoteAnimationTarget>,
                nonApps: Array<RemoteAnimationTarget>,
                finishedCallback: IRemoteAnimationFinishedCallback?
            ) {
                Log.d(TAG, "unoccludeAnimationRunner#onAnimationStart")
                // Wrap the callback so that it's guaranteed to be nulled out once called.
                unoccludeAnimationFinishedCallback =
                    object : IRemoteAnimationFinishedCallback.Stub() {
                        override fun onAnimationFinished() {
                            finishedCallback?.onAnimationFinished()
                            unoccludeAnimationFinishedCallback = null
                        }
                    }
                keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
                    showWhenLockedActivityOnTop = false,
                    taskInfo = apps.firstOrNull()?.taskInfo,
                )
                interactionJankMonitor.begin(
                    createInteractionJankMonitorConf(
                        InteractionJankMonitor.CUJ_LOCKSCREEN_OCCLUSION,
                        "UNOCCLUDE"
                    )
                )
                if (apps.isEmpty()) {
                    Log.d(
                        TAG,
                        "No apps provided to unocclude runner; " +
                            "skipping animation and unoccluding."
                    )
                    unoccludeAnimationFinishedCallback?.onAnimationFinished()
                    return
                }
                val target = apps[0]
                val localView: View = keyguardViewController.get().getViewRootImpl().getView()
                val applier = SyncRtSurfaceTransactionApplier(localView)
                // TODO(
                executor.execute {
                    unoccludeAnimator?.cancel()
                    unoccludeAnimator =
                        ValueAnimator.ofFloat(1f, 0f).apply {
                            duration = UNOCCLUDE_ANIMATION_DURATION.toLong()
                            interpolator = Interpolators.TOUCH_RESPONSE
                            addUpdateListener { animation: ValueAnimator ->
                                val animatedValue = animation.animatedValue as Float
                                val surfaceHeight: Float =
                                    target.screenSpaceBounds.height().toFloat()

                                unoccludeMatrix.setTranslate(
                                    0f,
                                    (1f - animatedValue) *
                                        surfaceHeight *
                                        UNOCCLUDE_TRANSLATE_DISTANCE_PERCENT
                                )

                                SurfaceParams.Builder(target.leash)
                                    .withAlpha(animatedValue)
                                    .withMatrix(unoccludeMatrix)
                                    .withCornerRadius(windowCornerRadius)
                                    .build()
                                    .also { applier.scheduleApply(it) }
                            }
                            addListener(
                                object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) {
                                        try {
                                            unoccludeAnimationFinishedCallback
                                                ?.onAnimationFinished()
                                            unoccludeAnimator = null
                                            interactionJankMonitor.end(
                                                InteractionJankMonitor.CUJ_LOCKSCREEN_OCCLUSION
                                            )
                                        } catch (e: RemoteException) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            )
                            start()
                        }
                }
            }

            override fun onAnimationCancelled() {
                Log.d(TAG, "unoccludeAnimationRunner#onAnimationCancelled")
                context.mainExecutor.execute { unoccludeAnimator?.cancel() }
                Log.d(TAG, "Unocclude animation cancelled.")
                interactionJankMonitor.cancel(InteractionJankMonitor.CUJ_LOCKSCREEN_OCCLUSION)
            }
        }

    /**
     * Called when Window Manager tells the KeyguardService directly that we're occluded or not
     * occluded, without starting an occlude/unocclude remote animation. This happens if occlusion
     * state changes without an animation (such as if a SHOW_WHEN_LOCKED activity is launched while
     * we're unlocked), or if an animation has been cancelled/interrupted and Window Manager wants
     * to make sure that we're in the correct state.
     */
    fun onKeyguardServiceSetOccluded(occluded: Boolean) {
        Log.d(TAG, "#onKeyguardServiceSetOccluded($occluded)")
        keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(occluded)
    }

    @VisibleForTesting
    val occludeAnimationController: ActivityTransitionAnimator.Controller =
        object : ActivityTransitionAnimator.Controller {
            override val isLaunching: Boolean = true

            override var transitionContainer: ViewGroup
                get() = keyguardViewController.get().getViewRootImpl().view as ViewGroup
                set(_) {
                    // Should never be set.
                }

            /** TODO(b/326470033): Extract this logic into ViewModels. */
            override fun createAnimatorState(): TransitionAnimator.State {
                val fullWidth = transitionContainer.width
                val fullHeight = transitionContainer.height

                if (
                    keyguardOcclusionInteractor.showWhenLockedActivityLaunchedFromPowerGesture.value
                ) {
                    val initialHeight = fullHeight / 3f
                    val initialWidth = fullWidth / 3f

                    // Start the animation near the power button, at one-third size, since the
                    // camera was launched from the power button.
                    return TransitionAnimator.State(
                        top = (powerButtonY - initialHeight / 2f).toInt(),
                        bottom = (powerButtonY + initialHeight / 2f).toInt(),
                        left = (fullWidth - initialWidth).toInt(),
                        right = fullWidth,
                        topCornerRadius = windowCornerRadius,
                        bottomCornerRadius = windowCornerRadius,
                    )
                } else {
                    val initialHeight = fullHeight / 2f
                    val initialWidth = fullWidth / 2f

                    // Start the animation in the center of the screen, scaled down to half
                    // size.
                    return TransitionAnimator.State(
                        top = (fullHeight - initialHeight).toInt() / 2,
                        bottom = (initialHeight + (fullHeight - initialHeight) / 2).toInt(),
                        left = (fullWidth - initialWidth).toInt() / 2,
                        right = (initialWidth + (fullWidth - initialWidth) / 2).toInt(),
                        topCornerRadius = windowCornerRadius,
                        bottomCornerRadius = windowCornerRadius,
                    )
                }
            }
        }

    private fun createInteractionJankMonitorConf(
        cuj: Int,
        tag: String?
    ): InteractionJankMonitor.Configuration.Builder {
        val builder =
            InteractionJankMonitor.Configuration.Builder.withView(
                cuj,
                keyguardViewController.get().getViewRootImpl().view
            )
        return if (tag != null) builder.setTag(tag) else builder
    }

    companion object {
        val TAG = "WindowManagerOcclusion"
    }
}
