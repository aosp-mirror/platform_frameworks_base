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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.ambient.touch.TouchMonitor
import com.android.systemui.ambient.touch.dagger.AmbientTouchComponent
import com.android.systemui.communal.dagger.Communal
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.ui.compose.CommunalContainer
import com.android.systemui.communal.ui.compose.CommunalContent
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.android.systemui.util.kotlin.collectFlow
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Controller that's responsible for the glanceable hub container view and its touch handling.
 *
 * This will be used until the glanceable hub is integrated into Flexiglass.
 */
class GlanceableHubContainerController
@Inject
constructor(
    private val communalInteractor: CommunalInteractor,
    private val communalViewModel: CommunalViewModel,
    private val keyguardInteractor: KeyguardInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val powerManager: PowerManager,
    private val communalColors: CommunalColors,
    private val ambientTouchComponentFactory: AmbientTouchComponent.Factory,
    private val communalContent: CommunalContent,
    @Communal private val dataSourceDelegator: SceneDataSourceDelegator
) : LifecycleOwner {
    /** The container view for the hub. This will not be initialized until [initView] is called. */
    private var communalContainerView: View? = null

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
     * The width of the area in which a right edge swipe can open the hub, in pixels. Read from
     * resources when [initView] is called.
     */
    // TODO(b/320786721): support RTL layouts
    private var rightEdgeSwipeRegionWidth: Int = 0

    /**
     * True if we are currently tracking a touch intercepted by the hub, either because the hub is
     * open or being opened.
     */
    private var isTrackingHubTouch = false

    /**
     * True if the hub UI is fully open, meaning it should receive touch input.
     *
     * Tracks [CommunalInteractor.isCommunalShowing].
     */
    private var hubShowing = false

    /**
     * True if either the primary or alternate bouncer are open, meaning the hub should not receive
     * any touch input.
     *
     * Tracks [KeyguardTransitionInteractor.isFinishedInState] for [KeyguardState.isBouncerState].
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
    private var shadeShowing = false

    /**
     * True if the device is dreaming, in which case we shouldn't do anything for top/bottom swipes
     * and just let the dream overlay's touch handling deal with them.
     *
     * Tracks [KeyguardInteractor.isDreaming].
     */
    private var isDreaming = false

    /** Returns a flow that tracks whether communal hub is available. */
    fun communalAvailable(): Flow<Boolean> =
        anyOf(communalInteractor.isCommunalAvailable, communalInteractor.editModeOpen)

    /**
     * Creates the container view containing the glanceable hub UI.
     *
     * @throws RuntimeException if the view is already initialized
     */
    fun initView(
        context: Context,
    ): View {
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

    /** Override for testing. */
    @VisibleForTesting
    internal fun initView(containerView: View): View {
        SceneContainerFlag.assertInLegacyMode()
        if (communalContainerView != null) {
            throw RuntimeException("Communal view has already been initialized")
        }

        if (touchMonitor == null) {
            touchMonitor =
                ambientTouchComponentFactory.create(this, HashSet()).getTouchMonitor().apply {
                    init()
                }
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        communalContainerView = containerView

        rightEdgeSwipeRegionWidth =
            containerView.resources.getDimensionPixelSize(
                R.dimen.communal_right_edge_swipe_region_width
            )

        val topEdgeSwipeRegionWidth =
            containerView.resources.getDimensionPixelSize(
                R.dimen.communal_top_edge_swipe_region_height
            )
        val bottomEdgeSwipeRegionWidth =
            containerView.resources.getDimensionPixelSize(
                R.dimen.communal_bottom_edge_swipe_region_height
            )

        // BouncerSwipeTouchHandler has a larger gesture area than we want, set an exclusion area so
        // the gesture area doesn't overlap with widgets.
        // TODO(b/323035776): adjust gesture area for portrait mode
        containerView.repeatWhenAttached {
            // Run when the touch handling lifecycle is RESUMED, meaning the hub is visible and not
            // occluded.
            lifecycleRegistry.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val exclusionRect =
                    Rect(
                        0,
                        topEdgeSwipeRegionWidth,
                        containerView.right,
                        containerView.bottom - bottomEdgeSwipeRegionWidth
                    )

                containerView.systemGestureExclusionRects = listOf(exclusionRect)
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
                keyguardInteractor.alternateBouncerShowing
            ),
            {
                anyBouncerShowing = it
                updateTouchHandlingState()
            }
        )
        collectFlow(
            containerView,
            communalInteractor.isCommunalVisible,
            {
                hubShowing = it
                updateTouchHandlingState()
            }
        )
        collectFlow(
            containerView,
            allOf(shadeInteractor.isAnyFullyExpanded, not(shadeInteractor.isUserInteracting)),
            {
                shadeShowing = it
                updateTouchHandlingState()
            }
        )
        collectFlow(containerView, keyguardInteractor.isDreaming, { isDreaming = it })

        communalContainerView = containerView

        return containerView
    }

    /**
     * Updates the lifecycle stored by the [lifecycleRegistry] to control when the [touchMonitor]
     * should listen for and intercept top and bottom swipes.
     *
     * Also clears gesture exclusion zones when the hub is occluded or gone.
     */
    private fun updateTouchHandlingState() {
        val shouldInterceptGestures = hubShowing && !(shadeShowing || anyBouncerShowing)
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
        return communalContainerView?.let { handleTouchEventOnCommunalView(it, ev) } ?: false
    }

    private fun handleTouchEventOnCommunalView(view: View, ev: MotionEvent): Boolean {
        val isDown = ev.actionMasked == MotionEvent.ACTION_DOWN
        val isUp = ev.actionMasked == MotionEvent.ACTION_UP
        val isCancel = ev.actionMasked == MotionEvent.ACTION_CANCEL

        val hubOccluded = anyBouncerShowing || shadeShowing

        if (isDown && !hubOccluded) {
            val x = ev.rawX
            val inOpeningSwipeRegion: Boolean = x >= view.width - rightEdgeSwipeRegionWidth
            if (inOpeningSwipeRegion || hubShowing) {
                // Steal touch events when the hub is open, or if the touch started in the opening
                // gesture region.
                isTrackingHubTouch = true
            }
        }

        if (isTrackingHubTouch) {
            if (isUp || isCancel) {
                isTrackingHubTouch = false
            }
            dispatchTouchEvent(view, ev)
            // Return true regardless of dispatch result as some touches at the start of a gesture
            // may return false from dispatchTouchEvent.
            return true
        }

        return false
    }

    /**
     * Dispatches the touch event to the communal container and sends a user activity event to reset
     * the screen timeout.
     */
    private fun dispatchTouchEvent(view: View, ev: MotionEvent) {
        view.dispatchTouchEvent(ev)
        powerManager.userActivity(
            SystemClock.uptimeMillis(),
            PowerManager.USER_ACTIVITY_EVENT_TOUCH,
            0
        )
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
