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
package com.android.systemui.ambient.touch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.Region
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.InputEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.Flags
import com.android.systemui.ambient.touch.TouchHandler.TouchSession
import com.android.systemui.ambient.touch.dagger.BouncerSwipeModule
import com.android.systemui.ambient.touch.scrim.ScrimController
import com.android.systemui.ambient.touch.scrim.ScrimManager
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.wm.shell.animation.FlingAnimationUtils
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Monitor for tracking touches on the DreamOverlay to bring up the bouncer. */
class BouncerSwipeTouchHandler
@Inject
constructor(
    scope: CoroutineScope,
    private val scrimManager: ScrimManager,
    private val centralSurfaces: Optional<CentralSurfaces>,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val valueAnimatorCreator: ValueAnimatorCreator,
    private val velocityTrackerFactory: VelocityTrackerFactory,
    private val communalViewModel: CommunalViewModel,
    @param:Named(BouncerSwipeModule.SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_OPENING)
    private val flingAnimationUtils: FlingAnimationUtils,
    @param:Named(BouncerSwipeModule.SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_CLOSING)
    private val flingAnimationUtilsClosing: FlingAnimationUtils,
    @param:Named(BouncerSwipeModule.SWIPE_TO_BOUNCER_START_REGION)
    private val bouncerZoneScreenPercentage: Float,
    @param:Named(BouncerSwipeModule.MIN_BOUNCER_ZONE_SCREEN_PERCENTAGE)
    private val minBouncerZoneScreenPercentage: Float,
    private val uiEventLogger: UiEventLogger,
    private val activityStarter: ActivityStarter,
    private val keyguardInteractor: KeyguardInteractor,
) : TouchHandler {
    /** An interface for creating ValueAnimators. */
    interface ValueAnimatorCreator {
        /** Creates [ValueAnimator]. */
        fun create(start: Float, finish: Float): ValueAnimator
    }

    /** An interface for obtaining VelocityTrackers. */
    interface VelocityTrackerFactory {
        /** Obtains [VelocityTracker]. */
        fun obtain(): VelocityTracker?
    }

    private var currentScrimController: ScrimController? = null
    private var currentExpansion = 0f
    private var velocityTracker: VelocityTracker? = null
    private var capture: Boolean? = null
    private var expanded: Boolean = false
    private var touchSession: TouchSession? = null
    private val scrimManagerCallback =
        ScrimManager.Callback { controller ->
            currentScrimController?.reset()

            currentScrimController = controller
        }

    /** Determines whether the touch handler should process touches in fullscreen swiping mode */
    private var touchAvailable = false

    private val onGestureListener: GestureDetector.OnGestureListener =
        object : SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (capture == null) {
                    capture =
                        if (Flags.dreamOverlayBouncerSwipeDirectionFiltering()) {
                            (abs(distanceY.toDouble()) > abs(distanceX.toDouble()) &&
                                distanceY > 0) &&
                                if (Flags.hubmodeFullscreenVerticalSwipeFix()) touchAvailable
                                else true
                        } else {
                            // If the user scrolling favors a vertical direction, begin capturing
                            // scrolls.
                            abs(distanceY.toDouble()) > abs(distanceX.toDouble())
                        }
                    if (capture == true) {
                        // reset expanding
                        expanded = false
                        // Since the user is dragging the bouncer up, set scrimmed to false.
                        currentScrimController?.show()
                    }
                }
                if (capture != true) {
                    return false
                }

                if (!centralSurfaces.isPresent) {
                    return true
                }

                e1?.apply outer@{
                    // Don't set expansion for downward scroll.
                    if (y < e2.y) {
                        return true
                    }

                    // If scrolling up and keyguard is not locked, dismiss both keyguard and the
                    // dream since there's no bouncer to show.
                    if (y > e2.y && keyguardInteractor.isKeyguardDismissible.value) {
                        activityStarter.executeRunnableDismissingKeyguard(
                            { centralSurfaces.get().awakenDreams() },
                            /* cancelAction= */ null,
                            /* dismissShade= */ true,
                            /* afterKeyguardGone= */ true,
                            /* deferred= */ false
                        )
                        return true
                    }

                    // For consistency, we adopt the expansion definition found in the
                    // PanelViewController. In this case, expansion refers to the view above the
                    // bouncer. As that view's expansion shrinks, the bouncer appears. The bouncer
                    // is fully hidden at full expansion (1) and fully visible when fully collapsed
                    // (0).
                    touchSession?.apply {
                        val screenTravelPercentage =
                            (abs((this@outer.y - e2.y).toDouble()) / getBounds().height()).toFloat()
                        setPanelExpansion(1 - screenTravelPercentage)
                    }
                }

                return true
            }
        }

    init {
        if (Flags.hubmodeFullscreenVerticalSwipeFix()) {
            scope.launch {
                communalViewModel.glanceableTouchAvailable.collect {
                    onGlanceableTouchAvailable(it)
                }
            }
        }
    }

    @VisibleForTesting
    fun onGlanceableTouchAvailable(available: Boolean) {
        touchAvailable = available
    }

    private fun setPanelExpansion(expansion: Float) {
        currentExpansion = expansion
        val event =
            ShadeExpansionChangeEvent(
                /* fraction= */ currentExpansion,
                /* expanded= */ expanded,
                /* tracking= */ true
            )
        currentScrimController?.expand(event)
    }

    @VisibleForTesting
    enum class DreamEvent(private val mId: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The screensaver has been swiped up.") DREAM_SWIPED(988),
        @UiEvent(doc = "The bouncer has become fully visible over dream.")
        DREAM_BOUNCER_FULLY_VISIBLE(1056);

        override fun getId(): Int {
            return mId
        }
    }

    override fun getTouchInitiationRegion(bounds: Rect, region: Region, exclusionRect: Rect?) {
        val width = bounds.width()
        val height = bounds.height()
        val minAllowableBottom = Math.round(height * (1 - minBouncerZoneScreenPercentage))
        val normalRegion =
            Rect(0, Math.round(height * (1 - bouncerZoneScreenPercentage)), width, height)

        if (Flags.hubmodeFullscreenVerticalSwipeFix()) {
            region.op(bounds, Region.Op.UNION)
            exclusionRect?.apply { region.op(this, Region.Op.DIFFERENCE) }
        }

        if (exclusionRect != null) {
            val lowestBottom =
                min(max(0.0, exclusionRect.bottom.toDouble()), minAllowableBottom.toDouble())
                    .toInt()
            normalRegion.top = max(normalRegion.top.toDouble(), lowestBottom.toDouble()).toInt()
        }
        region.union(normalRegion)
    }

    override fun onSessionStart(session: TouchSession) {
        velocityTracker = velocityTrackerFactory.obtain()
        touchSession = session
        velocityTracker?.apply { clear() }
        if (!Flags.communalBouncerDoNotModifyPluginOpen()) {
            notificationShadeWindowController.setForcePluginOpen(true, this)
        }
        scrimManager.addCallback(scrimManagerCallback)
        currentScrimController = scrimManager.currentController
        session.registerCallback {
            velocityTracker?.apply { recycle() }
            velocityTracker = null

            scrimManager.removeCallback(scrimManagerCallback)
            capture = null
            touchSession = null
            if (!Flags.communalBouncerDoNotModifyPluginOpen()) {
                notificationShadeWindowController.setForcePluginOpen(false, this)
            }
        }
        session.registerGestureListener(onGestureListener)
        session.registerInputListener { ev: InputEvent -> onMotionEvent(ev) }
    }

    private fun onMotionEvent(event: InputEvent) {
        if (event !is MotionEvent) {
            Log.e(TAG, "non MotionEvent received:$event")
            return
        }
        val motionEvent = event
        when (motionEvent.action) {
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                if (Flags.hubmodeFullscreenVerticalSwipeFix() && capture == true) {
                    communalViewModel.onResetTouchState()
                }
                touchSession?.apply { pop() }
                // If we are not capturing any input, there is no need to consider animating to
                // finish transition.
                if (capture == null || !capture!!) {
                    return
                }

                // We must capture the resulting velocities as resetMonitor() will clear these
                // values.
                velocityTracker!!.computeCurrentVelocity(1000)
                val verticalVelocity = velocityTracker!!.yVelocity
                val horizontalVelocity = velocityTracker!!.xVelocity
                val velocityVector =
                    hypot(horizontalVelocity.toDouble(), verticalVelocity.toDouble()).toFloat()
                expanded = !flingRevealsOverlay(verticalVelocity, velocityVector)
                val expansion =
                    if (expanded!!) KeyguardBouncerConstants.EXPANSION_VISIBLE
                    else KeyguardBouncerConstants.EXPANSION_HIDDEN

                // Log the swiping up to show Bouncer event.
                if (expansion == KeyguardBouncerConstants.EXPANSION_VISIBLE) {
                    uiEventLogger.log(DreamEvent.DREAM_SWIPED)
                }
                flingToExpansion(verticalVelocity, expansion)
            }
            else -> velocityTracker!!.addMovement(motionEvent)
        }
    }

    private fun createExpansionAnimator(targetExpansion: Float): ValueAnimator {
        val animator = valueAnimatorCreator.create(currentExpansion, targetExpansion)
        animator.addUpdateListener { animation: ValueAnimator ->
            val expansionFraction = animation.animatedValue as Float
            setPanelExpansion(expansionFraction)
        }
        if (targetExpansion == KeyguardBouncerConstants.EXPANSION_VISIBLE) {
            animator.addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        uiEventLogger.log(DreamEvent.DREAM_BOUNCER_FULLY_VISIBLE)
                    }
                }
            )
        }
        return animator
    }

    protected fun flingRevealsOverlay(velocity: Float, velocityVector: Float): Boolean {
        // Fully expand the space above the bouncer, if the user has expanded the bouncer less
        // than halfway or final velocity was positive, indicating a downward direction.
        return if (abs(velocityVector.toDouble()) < flingAnimationUtils.minVelocityPxPerSecond) {
            currentExpansion > FLING_PERCENTAGE_THRESHOLD
        } else {
            velocity > 0
        }
    }

    protected fun flingToExpansion(velocity: Float, expansion: Float) {
        if (!centralSurfaces.isPresent) {
            return
        }

        // Don't set expansion if keyguard is dismissible (i.e. unlocked).
        if (keyguardInteractor.isKeyguardDismissible.value) {
            return
        }

        touchSession?.apply {
            // The animation utils deal in pixel units, rather than expansion height.
            val viewHeight = getBounds().height().toFloat()
            val currentHeight = viewHeight * currentExpansion
            val targetHeight = viewHeight * expansion
            val animator = createExpansionAnimator(expansion)
            if (expansion == KeyguardBouncerConstants.EXPANSION_HIDDEN) {
                // Hides the bouncer, i.e., fully expands the space above the bouncer.
                flingAnimationUtilsClosing.apply(
                    animator,
                    currentHeight,
                    targetHeight,
                    velocity,
                    viewHeight
                )
            } else {
                // Shows the bouncer, i.e., fully collapses the space above the bouncer.
                flingAnimationUtils.apply(
                    animator,
                    currentHeight,
                    targetHeight,
                    velocity,
                    viewHeight
                )
            }
            animator.start()
        }
    }

    companion object {
        const val FLING_PERCENTAGE_THRESHOLD = 0.5f
        private const val TAG = "BouncerSwipeTouchHandler"
    }
}
