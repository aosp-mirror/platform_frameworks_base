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
package com.android.systemui.statusbar.phone

import android.app.StatusBarManager.WINDOW_STATUS_BAR
import android.graphics.Point
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.VisibleForTesting
import com.android.systemui.Flags
import com.android.systemui.Gefingerpoken
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.ENABLE_UNFOLD_STATUS_BAR_ANIMATIONS
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeLogger
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shared.animation.UnfoldMoveFromCenterAnimator
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.unfold.UNFOLD_STATUS_BAR
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel
import com.android.systemui.util.ViewController
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.view.ViewUtil
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

private const val TAG = "PhoneStatusBarViewController"

/** Controller for [PhoneStatusBarView]. */
class PhoneStatusBarViewController
private constructor(
    view: PhoneStatusBarView,
    @Named(UNFOLD_STATUS_BAR) private val progressProvider: ScopedUnfoldTransitionProgressProvider?,
    private val centralSurfaces: CentralSurfaces,
    private val statusBarWindowStateController: StatusBarWindowStateController,
    private val shadeController: ShadeController,
    private val shadeViewController: ShadeViewController,
    private val panelExpansionInteractor: PanelExpansionInteractor,
    private val windowRootView: Provider<WindowRootView>,
    private val shadeLogger: ShadeLogger,
    private val moveFromCenterAnimationController: StatusBarMoveFromCenterAnimationController?,
    private val userChipViewModel: StatusBarUserChipViewModel,
    private val viewUtil: ViewUtil,
    private val configurationController: ConfigurationController,
    private val statusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory,
    private val darkIconDispatcher: DarkIconDispatcher,
    private val statusBarContentInsetsProvider: StatusBarContentInsetsProvider,
) : ViewController<PhoneStatusBarView>(view) {

    private lateinit var battery: BatteryMeterView
    private lateinit var clock: Clock
    private lateinit var startSideContainer: View
    private lateinit var endSideContainer: View

    private val iconsOnTouchListener =
        object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                // We want to handle only mouse events here to avoid stealing finger touches
                // from status bar which expands shade when swiped down on. See b/326097469.
                // We're using onTouchListener instead of onClickListener as the later will lead
                // to isClickable being set to true and hence ALL touches always being
                // intercepted. See [View.OnTouchEvent]
                if (event.source == InputDevice.SOURCE_MOUSE) {
                    if (event.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                        shadeController.animateExpandShade()
                    }
                    return true
                }
                return false
            }
        }

    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onDensityOrFontScaleChanged() {
                clock.onDensityOrFontScaleChanged()
            }
        }

    override fun onViewAttached() {
        clock = mView.requireViewById(R.id.clock)
        battery = mView.requireViewById(R.id.battery)
        addDarkReceivers()
        addCursorSupportToIconContainers()

        progressProvider?.setReadyToHandleTransition(true)
        configurationController.addCallback(configurationListener)

        if (moveFromCenterAnimationController == null) return

        val statusBarLeftSide: View =
            mView.requireViewById(R.id.status_bar_start_side_except_heads_up)
        val systemIconArea: ViewGroup = mView.requireViewById(R.id.status_bar_end_side_content)

        val viewsToAnimate = arrayOf(statusBarLeftSide, systemIconArea)

        mView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    moveFromCenterAnimationController.onViewsReady(viewsToAnimate)
                    mView.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            }
        )

        mView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val widthChanged = right - left != oldRight - oldLeft
            if (widthChanged) {
                moveFromCenterAnimationController.onStatusBarWidthChanged()
            }
        }
    }

    private fun addCursorSupportToIconContainers() {
        endSideContainer = mView.requireViewById(R.id.system_icons)
        endSideContainer.setOnHoverListener(
            statusOverlayHoverListenerFactory.createDarkAwareListener(endSideContainer)
        )
        endSideContainer.setOnTouchListener(iconsOnTouchListener)

        startSideContainer = mView.requireViewById(R.id.status_bar_start_side_content)
        startSideContainer.setOnHoverListener(
            statusOverlayHoverListenerFactory.createDarkAwareListener(startSideContainer)
        )
        startSideContainer.setOnTouchListener(iconsOnTouchListener)
    }

    @VisibleForTesting
    public override fun onViewDetached() {
        removeDarkReceivers()
        startSideContainer.setOnHoverListener(null)
        endSideContainer.setOnHoverListener(null)
        progressProvider?.setReadyToHandleTransition(false)
        moveFromCenterAnimationController?.onViewDetached()
        configurationController.removeCallback(configurationListener)
    }

    init {
        // These should likely be done in `onInit`, not `init`.
        mView.setTouchEventHandler(PhoneStatusBarViewTouchHandler())
        mView.setHasCornerCutoutFetcher {
            statusBarContentInsetsProvider.currentRotationHasCornerCutout()
        }
        mView.setInsetsFetcher {
            statusBarContentInsetsProvider.getStatusBarContentInsetsForCurrentRotation()
        }
        mView.init(userChipViewModel)
    }

    override fun onInit() {}

    fun setImportantForAccessibility(mode: Int) {
        mView.importantForAccessibility = mode
    }

    /**
     * Sends a touch event to the status bar view.
     *
     * This is required in certain cases because the status bar view is in a separate window from
     * the rest of SystemUI, and other windows may decide that their touch should instead be treated
     * as a status bar window touch.
     */
    fun sendTouchToView(ev: MotionEvent): Boolean {
        return mView.dispatchTouchEvent(ev)
    }

    /**
     * Returns true if the given (x, y) point (in screen coordinates) is within the status bar
     * view's range and false otherwise.
     */
    fun touchIsWithinView(x: Float, y: Float): Boolean {
        return viewUtil.touchIsWithinView(mView, x, y)
    }

    /** Called when a touch event occurred on {@link PhoneStatusBarView}. */
    fun onTouch(event: MotionEvent) {
        if (statusBarWindowStateController.windowIsShowing()) {
            val upOrCancel =
                event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL
            centralSurfaces.setInteracting(
                WINDOW_STATUS_BAR,
                !upOrCancel || shadeController.isExpandedVisible
            )
        }
    }

    private fun addDarkReceivers() {
        darkIconDispatcher.addDarkReceiver(battery)
        darkIconDispatcher.addDarkReceiver(clock)
    }

    private fun removeDarkReceivers() {
        darkIconDispatcher.removeDarkReceiver(battery)
        darkIconDispatcher.removeDarkReceiver(clock)
    }

    inner class PhoneStatusBarViewTouchHandler : Gefingerpoken {
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            return if (Flags.statusBarSwipeOverChip()) {
                shadeViewController.handleExternalInterceptTouch(event)
            } else {
                onTouch(event)
                false
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            onTouch(event)

            // If panels aren't enabled, ignore the gesture and don't pass it down to the
            // panel view.
            if (!centralSurfaces.commandQueuePanelsEnabled) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.v(
                        TAG,
                        String.format(
                            "onTouchForwardedFromStatusBar: panel disabled, " +
                                "ignoring touch at (${event.x.toInt()},${event.y.toInt()})"
                        )
                    )
                }
                return false
            }

            // If scene framework is enabled, route the touch to it and
            // ignore the rest of the gesture.
            if (SceneContainerFlag.isEnabled) {
                windowRootView.get().dispatchTouchEvent(event)
                return true
            }

            if (event.action == MotionEvent.ACTION_DOWN) {
                // If the view that would receive the touch is disabled, just have status
                // bar eat the gesture.
                if (!shadeViewController.isViewEnabled) {
                    shadeLogger.logMotionEvent(
                        event,
                        "onTouchForwardedFromStatusBar: panel view disabled"
                    )
                    return true
                }
                if (panelExpansionInteractor.isFullyCollapsed && event.y < 1f) {
                    // b/235889526 Eat events on the top edge of the phone when collapsed
                    shadeLogger.logMotionEvent(event, "top edge touch ignored")
                    return true
                }
            }
            return shadeViewController.handleExternalTouch(event)
        }
    }

    class StatusBarViewsCenterProvider : UnfoldMoveFromCenterAnimator.ViewCenterProvider {
        override fun getViewCenter(view: View, outPoint: Point) =
            when (view.id) {
                R.id.status_bar_start_side_except_heads_up -> {
                    // items aligned to the start, return start center point
                    getViewEdgeCenter(view, outPoint, isStart = true)
                }
                R.id.status_bar_end_side_content -> {
                    // items aligned to the end, return end center point
                    getViewEdgeCenter(view, outPoint, isStart = false)
                }
                else -> super.getViewCenter(view, outPoint)
            }

        /** Returns start or end (based on [isStart]) center point of the view */
        private fun getViewEdgeCenter(view: View, outPoint: Point, isStart: Boolean) {
            val isRtl = view.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val isLeftEdge = isRtl xor isStart

            val viewLocation = IntArray(2)
            view.getLocationOnScreen(viewLocation)

            val viewX = viewLocation[0]
            val viewY = viewLocation[1]

            outPoint.x = viewX + if (isLeftEdge) view.height / 2 else view.width - view.height / 2
            outPoint.y = viewY + view.height / 2
        }
    }

    class Factory
    @Inject
    constructor(
        private val unfoldComponent: Optional<SysUIUnfoldComponent>,
        @Named(UNFOLD_STATUS_BAR)
        private val progressProvider: Optional<ScopedUnfoldTransitionProgressProvider>,
        private val featureFlags: FeatureFlags,
        private val userChipViewModel: StatusBarUserChipViewModel,
        private val centralSurfaces: CentralSurfaces,
        private val statusBarWindowStateController: StatusBarWindowStateController,
        private val shadeController: ShadeController,
        private val shadeViewController: ShadeViewController,
        private val panelExpansionInteractor: PanelExpansionInteractor,
        private val windowRootView: Provider<WindowRootView>,
        private val shadeLogger: ShadeLogger,
        private val viewUtil: ViewUtil,
        private val configurationController: ConfigurationController,
        private val statusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory,
        private val darkIconDispatcher: DarkIconDispatcher,
        private val statusBarContentInsetsProvider: StatusBarContentInsetsProvider,
    ) {
        fun create(view: PhoneStatusBarView): PhoneStatusBarViewController {
            val statusBarMoveFromCenterAnimationController =
                if (featureFlags.isEnabled(ENABLE_UNFOLD_STATUS_BAR_ANIMATIONS)) {
                    unfoldComponent.getOrNull()?.getStatusBarMoveFromCenterAnimationController()
                } else {
                    null
                }

            return PhoneStatusBarViewController(
                view,
                progressProvider.getOrNull(),
                centralSurfaces,
                statusBarWindowStateController,
                shadeController,
                shadeViewController,
                panelExpansionInteractor,
                windowRootView,
                shadeLogger,
                statusBarMoveFromCenterAnimationController,
                userChipViewModel,
                viewUtil,
                configurationController,
                statusOverlayHoverListenerFactory,
                darkIconDispatcher,
                statusBarContentInsetsProvider,
            )
        }
    }
}
