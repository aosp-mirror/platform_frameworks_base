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

package com.android.systemui.shade

import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.util.ArraySet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Flags
import com.android.systemui.ambient.touch.TouchMonitor
import com.android.systemui.ambient.touch.dagger.AmbientTouchComponent
import com.android.systemui.communal.dagger.Communal
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.ui.compose.CommunalContainer
import com.android.systemui.communal.ui.compose.CommunalContent
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalTouchLog
import com.android.systemui.media.controls.ui.controller.KeyguardMediaController
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.collectFlow
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Controller that's responsible for the glanceable hub container view and its touch handling.
 *
 * This will be used until the glanceable hub is integrated into Flexiglass.
 */
@SysUISingleton
class GlanceableHubContainerController
@Inject
constructor(
    private val communalInteractor: CommunalInteractor,
    private val communalViewModel: CommunalViewModel,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val powerManager: PowerManager,
    private val communalColors: CommunalColors,
    private val ambientTouchComponentFactory: AmbientTouchComponent.Factory,
    private val communalContent: CommunalContent,
    @Communal private val dataSourceDelegator: SceneDataSourceDelegator,
    private val notificationStackScrollLayoutController: NotificationStackScrollLayoutController,
    private val keyguardMediaController: KeyguardMediaController,
    private val lockscreenSmartspaceController: LockscreenSmartspaceController,
    @CommunalTouchLog logBuffer: LogBuffer,
) : LifecycleOwner {
    private val logger = Logger(logBuffer, TAG)

    private class CommunalWrapper(context: Context) : FrameLayout(context) {
        private val consumers: MutableSet<Consumer<Boolean>> = ArraySet()

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            consumers.forEach { it.accept(disallowIntercept) }
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }

        fun dispatchTouchEvent(
            ev: MotionEvent?,
            disallowInterceptConsumer: Consumer<Boolean>?,
        ): Boolean {
            disallowInterceptConsumer?.apply { consumers.add(this) }

            try {
                return super.dispatchTouchEvent(ev)
            } finally {
                consumers.clear()
            }
        }
    }

    /** The container view for the hub. This will not be initialized until [initView] is called. */
    private var communalContainerView: View? = null

    /** Wrapper around the communal container to intercept touch events */
    private var communalContainerWrapper: CommunalWrapper? = null

    /**
     * This lifecycle is used to control when the [touchMonitor] listens to touches. The lifecycle
     * should only be [Lifecycle.State.RESUMED] when the hub is showing and not covered by anything,
     * such as the notification shade or bouncer.
     */
    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    /**
     * This [TouchMonitor] listens for top and bottom swipe gestures globally when the hub is open.
     * When a top or bottom swipe is detected, they will be intercepted and used to open the
     * notification shade/bouncer.
     */
    private var touchMonitor: TouchMonitor? = null

    /**
     * True if we are currently tracking a touch intercepted by the hub, either because the hub is
     * open or being opened.
     */
    private var isTrackingHubTouch = false

    /**
     * True if a touch gesture on the lock screen has been consumed by the shade/bouncer and thus
     * should be ignored by the hub.
     *
     * This is necessary on the lock screen as gestures on an empty spot go through special touch
     * handling logic in [NotificationShadeWindowViewController] that decides if they should go to
     * the shade or bouncer. Once the shade or bouncer are moving, we don't get the typical cancel
     * event so to play nice, we ignore touches once we see the shade or bouncer are opening.
     */
    private var touchTakenByKeyguardGesture = false

    /**
     * True if the hub UI is fully open, meaning it should receive touch input.
     *
     * Tracks [CommunalInteractor.isCommunalShowing].
     */
    private var hubShowing = false

    /**
     * True if we're transitioning to or from edit mode
     *
     * We block all touches and gestures when edit mode is open to prevent funky transition issues
     * when entering and exiting edit mode because we delay exiting the hub scene when entering edit
     * mode and enter the hub scene early when exiting edit mode to make for a smoother transition.
     * Gestures during these transitions can result in broken and unexpected UI states.
     *
     * Tracks [CommunalInteractor.editActivityShowing] and the [KeyguardState.GONE] to
     * [KeyguardState.GLANCEABLE_HUB] transition.
     */
    private var inEditModeTransition = false

    /**
     * True if either the primary or alternate bouncer are open, meaning the hub should not receive
     * any touch input.
     */
    private var anyBouncerShowing = false

    /**
     * True if the shade is fully expanded and the user is not interacting with it anymore, meaning
     * the hub should not receive any touch input.
     *
     * We need to not pause the touch handling lifecycle as soon as the shade opens because if the
     * user swipes down, then back up without lifting their finger, the lifecycle will be paused
     * then resumed, and resuming force-stops all active touch sessions. This means the shade will
     * not receive the end of the gesture and will be stuck open.
     *
     * Based on [ShadeInteractor.isAnyFullyExpanded] and [ShadeInteractor.isUserInteracting].
     */
    private var shadeShowingAndConsumingTouches = false

    /**
     * True anytime the shade is processing user touches, regardless of expansion state.
     *
     * Based on [ShadeInteractor.isUserInteracting].
     */
    private var shadeConsumingTouches = false

    /**
     * True if the shade is showing at all.
     *
     * Inverse of [ShadeInteractor.isShadeFullyCollapsed]
     */
    private var shadeShowing = false

    /** True if the keyguard transition state is finished on [KeyguardState.LOCKSCREEN]. */
    private var onLockscreen = false

    /**
     * True if the shade ever fully expands and the user isn't interacting with it (aka finger on
     * screen dragging). In this case, the shade should handle all touch events until it has fully
     * collapsed.
     */
    private var userNotInteractiveAtShadeFullyExpanded = false

    /**
     * True if the device is dreaming, in which case we shouldn't do anything for top/bottom swipes
     * and just let the dream overlay's touch handling deal with them.
     *
     * Tracks [KeyguardInteractor.isDreaming].
     */
    private var isDreaming = false

    /** Observes and logs state when the lifecycle that controls the [touchMonitor] updates. */
    private val touchLifecycleLogger: LifecycleObserver = LifecycleEventObserver { _, event ->
        logger.d({
            "Touch handler lifecycle changed to $str1. hubShowing: $bool1, " +
                "shadeShowingAndConsumingTouches: $bool2, " +
                "anyBouncerShowing: $bool3, inEditModeTransition: $bool4"
        }) {
            str1 = event.toString()
            bool1 = hubShowing
            bool2 = shadeShowingAndConsumingTouches
            bool3 = anyBouncerShowing
            bool4 = inEditModeTransition
        }
    }

    /** Returns a flow that tracks whether communal hub is available. */
    fun communalAvailable(): Flow<Boolean> =
        anyOf(communalInteractor.isCommunalAvailable, communalInteractor.editModeOpen)

    /**
     * Creates the container view containing the glanceable hub UI.
     *
     * @throws RuntimeException if the view is already initialized
     */
    fun initView(context: Context): View {
        return initView(
            ComposeView(context).apply {
                repeatWhenAttached {
                    lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.CREATED) {
                            setViewTreeOnBackPressedDispatcherOwner(
                                object : OnBackPressedDispatcherOwner {
                                    override val onBackPressedDispatcher =
                                        OnBackPressedDispatcher().apply {
                                            setOnBackInvokedDispatcher(
                                                viewRootImpl.onBackInvokedDispatcher
                                            )
                                        }

                                    override val lifecycle: Lifecycle =
                                        this@repeatWhenAttached.lifecycle
                                }
                            )

                            setContent {
                                PlatformTheme {
                                    CommunalContainer(
                                        viewModel = communalViewModel,
                                        colors = communalColors,
                                        dataSourceDelegator = dataSourceDelegator,
                                        content = communalContent,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    private fun resetTouchMonitor() {
        touchMonitor?.apply {
            destroy()
            touchMonitor = null
        }
    }

    /** Override for testing. */
    @VisibleForTesting
    internal fun initView(containerView: View): View {
        SceneContainerFlag.assertInLegacyMode()
        if (communalContainerView != null) {
            throw RuntimeException("Communal view has already been initialized")
        }

        resetTouchMonitor()

        touchMonitor =
            ambientTouchComponentFactory.create(this, HashSet(), TAG).getTouchMonitor().apply {
                init()
            }

        lifecycleRegistry.addObserver(touchLifecycleLogger)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        communalContainerView = containerView

        if (!Flags.hubmodeFullscreenVerticalSwipeFix()) {
            val topEdgeSwipeRegionWidth =
                containerView.resources.getDimensionPixelSize(
                    R.dimen.communal_top_edge_swipe_region_height
                )
            val bottomEdgeSwipeRegionWidth =
                containerView.resources.getDimensionPixelSize(
                    R.dimen.communal_bottom_edge_swipe_region_height
                )

            // BouncerSwipeTouchHandler has a larger gesture area than we want, set an exclusion
            // area so
            // the gesture area doesn't overlap with widgets.
            // TODO(b/323035776): adjust gesture area for portrait mode
            containerView.repeatWhenAttached {
                // Run when the touch handling lifecycle is RESUMED, meaning the hub is visible and
                // not
                // occluded.
                lifecycleRegistry.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    containerView.systemGestureExclusionRects =
                        listOf(
                            // Only allow swipe up to bouncer and swipe down to shade in the very
                            // top/bottom to avoid conflicting with widgets in the hub grid.
                            Rect(
                                0,
                                topEdgeSwipeRegionWidth,
                                containerView.right,
                                containerView.bottom - bottomEdgeSwipeRegionWidth,
                            )
                        )

                    logger.d({ "Insets updated: $str1" }) {
                        str1 = containerView.systemGestureExclusionRects.toString()
                    }
                }
            }
        }

        // Listen to bouncer visibility directly as these flows become true as soon as any portion
        // of the bouncers are visible when the transition starts. The keyguard transition state
        // only changes once transitions are fully finished, which would mean touches during a
        // transition to the bouncer would be incorrectly intercepted by the hub.
        collectFlow(
            containerView,
            anyOf(
                keyguardInteractor.primaryBouncerShowing,
                keyguardInteractor.alternateBouncerShowing,
            ),
            {
                anyBouncerShowing = it
                if (hubShowing) {
                    logger.d({ "New value for anyBouncerShowing: $bool1" }) { bool1 = it }
                }
                updateTouchHandlingState()
            },
        )
        collectFlow(
            containerView,
            keyguardTransitionInteractor.isFinishedIn(KeyguardState.LOCKSCREEN),
            { onLockscreen = it },
        )
        collectFlow(
            containerView,
            communalInteractor.isCommunalVisible,
            {
                hubShowing = it
                updateTouchHandlingState()
            },
        )
        collectFlow(
            containerView,
            // When leaving edit mode, editActivityShowing is true until the edit mode activity
            // finishes itself and the device locks, after which isInTransition will be true until
            // we're fully on the hub.
            anyOf(
                communalInteractor.editActivityShowing,
                keyguardTransitionInteractor.isInTransition(
                    Edge.create(KeyguardState.GONE, KeyguardState.GLANCEABLE_HUB)
                ),
            ),
            {
                inEditModeTransition = it
                updateTouchHandlingState()
            },
        )
        collectFlow(
            containerView,
            combine(
                shadeInteractor.isAnyFullyExpanded,
                shadeInteractor.isUserInteracting,
                shadeInteractor.isShadeFullyCollapsed,
                ::Triple,
            ),
            { (isFullyExpanded, isUserInteracting, isShadeFullyCollapsed) ->
                shadeConsumingTouches = isUserInteracting
                shadeShowing = !isShadeFullyCollapsed
                val expandedAndNotInteractive = isFullyExpanded && !isUserInteracting

                // If we ever are fully expanded and not interacting, capture this state as we
                // should not handle touches until we fully collapse again
                userNotInteractiveAtShadeFullyExpanded =
                    !isShadeFullyCollapsed &&
                        (userNotInteractiveAtShadeFullyExpanded || expandedAndNotInteractive)

                // If the shade reaches full expansion without interaction, then we should allow it
                // to consume touches rather than handling it here until it disappears.
                shadeShowingAndConsumingTouches =
                    (userNotInteractiveAtShadeFullyExpanded || expandedAndNotInteractive).also {
                        if (it != shadeShowingAndConsumingTouches && hubShowing) {
                            logger.d({ "New value for shadeShowingAndConsumingTouches: $bool1" }) {
                                bool1 = it
                            }
                        }
                    }
                updateTouchHandlingState()
            },
        )
        collectFlow(containerView, keyguardInteractor.isDreaming, { isDreaming = it })

        communalContainerWrapper = CommunalWrapper(containerView.context)
        communalContainerWrapper?.addView(communalContainerView)
        logger.d("Hub container initialized")
        return communalContainerWrapper!!
    }

    /**
     * Updates the lifecycle stored by the [lifecycleRegistry] to control when the [touchMonitor]
     * should listen for and intercept top and bottom swipes.
     *
     * Also clears gesture exclusion zones when the hub is occluded or gone.
     */
    private fun updateTouchHandlingState() {
        // Only listen to gestures when we're settled in the hub keyguard state and the shade
        // bouncer are not showing on top.
        val shouldInterceptGestures =
            hubShowing &&
                !(shadeShowingAndConsumingTouches || anyBouncerShowing || inEditModeTransition)
        if (shouldInterceptGestures) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        } else {
            // Hub is either occluded or no longer showing, turn off touch handling.
            lifecycleRegistry.currentState = Lifecycle.State.STARTED

            // Clear exclusion rects if the hub is not showing or is covered, so we don't interfere
            // with back gestures when the bouncer or shade. We do this here instead of with
            // repeatOnLifecycle as repeatOnLifecycle does not run when going from RESUMED back to
            // STARTED, only when going from CREATED to STARTED.
            communalContainerView!!.systemGestureExclusionRects = emptyList()
        }
    }

    /** Removes the container view from its parent. */
    fun disposeView() {
        SceneContainerFlag.assertInLegacyMode()
        communalContainerView?.let {
            (it.parent as ViewGroup).removeView(it)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            communalContainerView = null
        }

        communalContainerWrapper?.let {
            (it.parent as ViewGroup).removeView(it)
            communalContainerWrapper = null
        }

        lifecycleRegistry.removeObserver(touchLifecycleLogger)

        resetTouchMonitor()

        logger.d("Hub container disposed")
    }

    /**
     * Notifies the hub container of a touch event. Returns true if it's determined that the touch
     * should go to the hub container and no one else.
     *
     * Special handling is needed because the hub container sits at the lowest z-order in
     * [NotificationShadeWindowView] and would not normally receive touches. We also cannot use a
     * [GestureDetector] as the hub container's SceneTransitionLayout is a Compose view that expects
     * to be fully in control of its own touch handling.
     */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        SceneContainerFlag.assertInLegacyMode()

        if (communalContainerView == null) {
            // Return early so we don't log unnecessarily and fill up our LogBuffer.
            return false
        }

        // In the case that we are handling full swipes on the lockscreen, are on the lockscreen,
        // and the touch is within the horizontal notification band on the screen, do not process
        // the touch.
        val touchOnNotifications =
            !notificationStackScrollLayoutController.isBelowLastNotification(ev.x, ev.y)
        val touchOnUmo = keyguardMediaController.isWithinMediaViewBounds(ev.x.toInt(), ev.y.toInt())
        val touchOnSmartspace =
            lockscreenSmartspaceController.isWithinSmartspaceBounds(ev.x.toInt(), ev.y.toInt())
        if (!hubShowing && (touchOnNotifications || touchOnUmo || touchOnSmartspace)) {
            logger.d({
                "Lockscreen touch ignored: touchOnNotifications: $bool1, touchOnUmo: $bool2, " +
                    "touchOnSmartspace: $bool3"
            }) {
                bool1 = touchOnNotifications
                bool2 = touchOnUmo
                bool3 = touchOnSmartspace
            }
            return false
        }

        return handleTouchEventOnCommunalView(ev)
    }

    private fun handleTouchEventOnCommunalView(ev: MotionEvent): Boolean {
        val isDown = ev.actionMasked == MotionEvent.ACTION_DOWN
        val isUp = ev.actionMasked == MotionEvent.ACTION_UP
        val isMove = ev.actionMasked == MotionEvent.ACTION_MOVE
        val isCancel = ev.actionMasked == MotionEvent.ACTION_CANCEL

        val hubOccluded = anyBouncerShowing || shadeConsumingTouches || shadeShowing

        if ((isDown || isMove) && !hubOccluded) {
            if (isDown) {
                logger.d({
                    "Touch started. x: $int1, y: $int2, hubShowing: $bool1, isDreaming: $bool2, " +
                        "onLockscreen: $bool3"
                }) {
                    int1 = ev.x.toInt()
                    int2 = ev.y.toInt()
                    bool1 = hubShowing
                    bool2 = isDreaming
                    bool3 = onLockscreen
                }
            }
            isTrackingHubTouch = true
        }

        if (isTrackingHubTouch) {
            // On the lock screen, our touch handlers are not active and we rely on the NSWVC's
            // touch handling for gestures on blank areas, which can go up to show the bouncer or
            // down to show the notification shade. We see the touches first and they are not
            // consumed and cancelled like on the dream or hub so we have to gracefully ignore them
            // if the shade or bouncer are handling them. This issue only applies to touches on the
            // keyguard itself, once the bouncer or shade are fully open, our logic stops us from
            // taking touches.
            touchTakenByKeyguardGesture =
                (onLockscreen && (shadeConsumingTouches || anyBouncerShowing)).also {
                    if (it != touchTakenByKeyguardGesture && it) {
                        logger.d(
                            "Lock screen touch consumed by shade or bouncer, ignoring " +
                                "subsequent touches"
                        )
                    }
                }
            if (isUp || isCancel) {
                logger.d({
                    val endReason = if (bool1) "up" else "cancel"
                    "Touch ended with $endReason. x: $int1, y: $int2, " +
                        "shadeConsumingTouches: $bool2, anyBouncerShowing: $bool3"
                }) {
                    int1 = ev.x.toInt()
                    int2 = ev.y.toInt()
                    bool1 = isUp
                    bool2 = shadeConsumingTouches
                    bool3 = anyBouncerShowing
                }
                isTrackingHubTouch = false

                // Clear out touch taken state to ensure the up/cancel event still gets dispatched
                // to the hub. This is necessary as the hub always receives at least the initial
                // down even if the shade or bouncer end up handling the touch.
                touchTakenByKeyguardGesture = false
            }
            return dispatchTouchEvent(ev)
        }

        return false
    }

    /**
     * Dispatches the touch event to the communal container and sends a user activity event to reset
     * the screen timeout.
     */
    private fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (inEditModeTransition) {
            // Consume but ignore touches while we're transitioning to or from edit mode so that the
            // user can't trigger another transition, such as by swiping the hub away, tapping a
            // widget, or opening the shade/bouncer. Doing any of these while transitioning can
            // result in broken states.
            return true
        }
        try {
            var handled = false
            if (!touchTakenByKeyguardGesture) {
                communalContainerWrapper?.dispatchTouchEvent(ev) {
                    if (it) {
                        handled = true
                    }
                }
            }
            return handled || hubShowing
        } finally {
            powerManager.userActivity(
                SystemClock.uptimeMillis(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH,
                0,
            )
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val TAG = "GlanceableHubContainer"
    }
}
