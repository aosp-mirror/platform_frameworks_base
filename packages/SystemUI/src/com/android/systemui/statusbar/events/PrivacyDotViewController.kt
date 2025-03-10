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

package com.android.systemui.statusbar.events

import android.annotation.UiThread
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.core.animation.Animator
import com.android.app.animation.Interpolators
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.annotations.GuardedBy
import com.android.systemui.ScreenDecorationsThread
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.data.repository.StatusBarContentInsetsProviderStore
import com.android.systemui.statusbar.events.PrivacyDotCorner.BottomLeft
import com.android.systemui.statusbar.events.PrivacyDotCorner.BottomRight
import com.android.systemui.statusbar.events.PrivacyDotCorner.TopLeft
import com.android.systemui.statusbar.events.PrivacyDotCorner.TopRight
import com.android.systemui.statusbar.phone.StatusBarContentInsetsChangedListener
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.leak.RotationUtils
import com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN
import com.android.systemui.util.leak.RotationUtils.Rotation
import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope

/**
 * Understands how to keep the persistent privacy dot in the corner of the screen in
 * ScreenDecorations, which does not rotate with the device.
 *
 * The basic principle here is that each dot will sit in a box that is equal to the margins of the
 * status bar (specifically the status_bar_contents view in PhoneStatusBarView). Each dot container
 * will have its gravity set towards the corner (i.e., top-right corner gets top|right gravity), and
 * the contained ImageView will be set to center_vertical and away from the corner horizontally. The
 * Views will match the status bar top padding and status bar height so that the dot can appear to
 * reside directly after the status bar system contents (basically after the battery).
 *
 * NOTE: any operation that modifies views directly must run on the provided executor, because these
 * views are owned by ScreenDecorations and it runs in its own thread
 */
interface PrivacyDotViewController {

    /**
     * Called when the [PrivacyDotViewController] should stop doing any work and clean up if needed.
     */
    fun stop()

    // Only can be modified on @UiThread
    var currentViewState: ViewState

    var showingListener: ShowingListener?

    @UiThread fun setNewRotation(rot: Int)

    @UiThread fun hideDotView(dot: View, animate: Boolean)

    @UiThread fun showDotView(dot: View, animate: Boolean)

    // Update the gravity and margins of the privacy views
    @UiThread fun updateRotations(rotation: Int, paddingTop: Int)

    @UiThread fun setCornerSizes(state: ViewState)

    fun initialize(topLeft: View, topRight: View, bottomLeft: View, bottomRight: View)

    @UiThread fun updateDotView(state: ViewState)

    interface ShowingListener {
        fun onPrivacyDotShown(v: View?)

        fun onPrivacyDotHidden(v: View?)
    }
}

open class PrivacyDotViewControllerImpl
@AssistedInject
constructor(
    @Main private val mainExecutor: Executor,
    @Assisted scope: CoroutineScope,
    private val stateController: StatusBarStateController,
    @Assisted private val configurationController: ConfigurationController,
    @Assisted private val contentInsetsProvider: StatusBarContentInsetsProvider,
    private val animationScheduler: SystemStatusAnimationScheduler,
    shadeInteractor: ShadeInteractor?,
    @ScreenDecorationsThread val uiExecutor: DelayableExecutor,
) : PrivacyDotViewController {
    private lateinit var tl: View
    private lateinit var tr: View
    private lateinit var bl: View
    private lateinit var br: View

    // Only can be modified on @UiThread
    override var currentViewState: ViewState = ViewState()

    @GuardedBy("lock")
    private var nextViewState: ViewState = currentViewState.copy()
        set(value) {
            field = value
            scheduleUpdate()
        }

    private val lock = Object()
    private var cancelRunnable: Runnable? = null

    private val views: Sequence<View>
        get() = if (!this::tl.isInitialized) sequenceOf() else sequenceOf(tl, tr, br, bl)

    override var showingListener: PrivacyDotViewController.ShowingListener? = null

    private val insetsChangedListener =
        object : StatusBarContentInsetsChangedListener {
            override fun onStatusBarContentInsetsChanged() {
                dlog("onStatusBarContentInsetsChanged: ")
                setNewLayoutRects()
            }
        }

    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onLayoutDirectionChanged(isRtl: Boolean) {
                uiExecutor.execute {
                    // If rtl changed, hide all dots until the next state resolves
                    setCornerVisibilities(View.INVISIBLE)

                    synchronized(this) {
                        val corner = selectDesignatedCorner(nextViewState.rotation, isRtl)
                        nextViewState =
                            nextViewState.copy(layoutRtl = isRtl, designatedCorner = corner)
                    }
                }
            }
        }

    private val statusBarStateListener =
        object : StatusBarStateController.StateListener {
            override fun onExpandedChanged(isExpanded: Boolean) {
                updateStatusBarState()
            }

            override fun onStateChanged(newState: Int) {
                updateStatusBarState()
            }
        }

    init {
        contentInsetsProvider.addCallback(insetsChangedListener)
        configurationController.addCallback(configurationListener)
        stateController.addCallback(statusBarStateListener)
        scope.launch {
            shadeInteractor?.isQsExpanded?.collect { isQsExpanded ->
                dlog("setQsExpanded $isQsExpanded")
                synchronized(lock) { nextViewState = nextViewState.copy(qsExpanded = isQsExpanded) }
            }
        }
    }

    override fun stop() {
        StatusBarConnectedDisplays.assertInNewMode()
        contentInsetsProvider.removeCallback(insetsChangedListener)
        configurationController.removeCallback(configurationListener)
        stateController.removeCallback(statusBarStateListener)
    }

    @UiThread
    override fun setNewRotation(rot: Int) {
        dlog("updateRotation: $rot")

        val isRtl: Boolean
        synchronized(lock) {
            if (rot == nextViewState.rotation) {
                return
            }

            isRtl = nextViewState.layoutRtl
        }

        // If we rotated, hide all dotes until the next state resolves
        setCornerVisibilities(View.INVISIBLE)

        val newCornerView = selectDesignatedCorner(rot, isRtl)
        val corner = newCornerView.corner()
        val paddingTop = contentInsetsProvider.getStatusBarPaddingTop(rot)

        synchronized(lock) {
            nextViewState =
                nextViewState.copy(
                    rotation = rot,
                    paddingTop = paddingTop,
                    designatedCorner = newCornerView,
                    corner = corner,
                )
        }
    }

    @UiThread
    override fun hideDotView(dot: View, animate: Boolean) {
        dot.clearAnimation()
        if (animate) {
            dot.animate()
                .setDuration(DURATION)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .alpha(0f)
                .withEndAction {
                    dot.visibility = View.INVISIBLE
                    showingListener?.onPrivacyDotHidden(dot)
                }
                .start()
        } else {
            dot.visibility = View.INVISIBLE
            showingListener?.onPrivacyDotHidden(dot)
        }
    }

    @UiThread
    override fun showDotView(dot: View, animate: Boolean) {
        dot.clearAnimation()
        if (animate) {
            dot.visibility = View.VISIBLE
            dot.alpha = 0f
            dot.animate()
                .alpha(1f)
                .setDuration(DURATION)
                .setInterpolator(Interpolators.ALPHA_IN)
                .start()
        } else {
            dot.visibility = View.VISIBLE
            dot.alpha = 1f
        }
        showingListener?.onPrivacyDotShown(dot)
    }

    @UiThread
    override fun updateRotations(rotation: Int, paddingTop: Int) {
        // To keep a view in the corner, its gravity is always the description of its current corner
        // Therefore, just figure out which view is in which corner. This turns out to be something
        // like (myCorner - rot) mod 4, where topLeft = 0, topRight = 1, etc. and portrait = 0, and
        // rotating the device counter-clockwise increments rotation by 1

        views.forEach { corner ->
            corner.setPadding(0, paddingTop, 0, 0)

            val rotatedCorner = cornerForView(corner).rotatedCorner(rotation)
            (corner.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = rotatedCorner.gravity
            }

            // Set the dot's view gravity to hug the status bar
            (corner.requireViewById<View>(R.id.privacy_dot).layoutParams
                    as FrameLayout.LayoutParams)
                .gravity = rotatedCorner.innerGravity
        }
    }

    @UiThread
    override fun setCornerSizes(state: ViewState) {
        // StatusBarContentInsetsProvider can tell us the location of the privacy indicator dot
        // in every rotation. The only thing we need to check is rtl
        val rtl = state.layoutRtl
        val size = Point()
        tl.context.display?.getRealSize(size)
        val currentRotation = RotationUtils.getExactRotation(tl.context)

        val displayWidth: Int
        val displayHeight: Int
        if (currentRotation == ROTATION_LANDSCAPE || currentRotation == ROTATION_SEASCAPE) {
            displayWidth = size.y
            displayHeight = size.x
        } else {
            displayWidth = size.x
            displayHeight = size.y
        }

        var rot = activeRotationForCorner(tl, rtl)
        var contentInsets = state.contentRectForRotation(rot)
        tl.setPadding(0, state.paddingTop, 0, 0)
        (tl.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = contentInsets.top
            height = contentInsets.height()
            if (rtl) {
                width = contentInsets.left
            } else {
                width = displayHeight - contentInsets.right
            }
        }

        rot = activeRotationForCorner(tr, rtl)
        contentInsets = state.contentRectForRotation(rot)
        tr.setPadding(0, state.paddingTop, 0, 0)
        (tr.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = contentInsets.top
            height = contentInsets.height()
            if (rtl) {
                width = contentInsets.left
            } else {
                width = displayWidth - contentInsets.right
            }
        }

        rot = activeRotationForCorner(br, rtl)
        contentInsets = state.contentRectForRotation(rot)
        br.setPadding(0, state.paddingTop, 0, 0)
        (br.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = contentInsets.top
            height = contentInsets.height()
            if (rtl) {
                width = contentInsets.left
            } else {
                width = displayHeight - contentInsets.right
            }
        }

        rot = activeRotationForCorner(bl, rtl)
        contentInsets = state.contentRectForRotation(rot)
        bl.setPadding(0, state.paddingTop, 0, 0)
        (bl.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = contentInsets.top
            height = contentInsets.height()
            if (rtl) {
                width = contentInsets.left
            } else {
                width = displayWidth - contentInsets.right
            }
        }
    }

    // Designated view will be the one at statusbar's view.END
    @UiThread
    private fun selectDesignatedCorner(r: Int, isRtl: Boolean): View? {
        if (!this::tl.isInitialized) {
            return null
        }

        return when (r) {
            0 -> if (isRtl) tl else tr
            1 -> if (isRtl) tr else br
            2 -> if (isRtl) br else bl
            3 -> if (isRtl) bl else tl
            else -> throw IllegalStateException("unknown rotation")
        }
    }

    // Track the current designated corner and maybe animate to a new rotation
    @UiThread
    private fun updateDesignatedCorner(newCorner: View?, shouldShowDot: Boolean) {
        if (shouldShowDot) {
            showingListener?.onPrivacyDotShown(newCorner)
            newCorner?.apply {
                clearAnimation()
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1.0f).setDuration(300).start()
            }
        }
    }

    @UiThread
    private fun setCornerVisibilities(vis: Int) {
        views.forEach { corner ->
            corner.visibility = vis
            if (vis == View.VISIBLE) {
                showingListener?.onPrivacyDotShown(corner)
            } else {
                showingListener?.onPrivacyDotHidden(corner)
            }
        }
    }

    private fun cornerForView(v: View): PrivacyDotCorner {
        return when (v) {
            tl -> TopLeft
            tr -> TopRight
            bl -> BottomLeft
            br -> BottomRight
            else -> throw IllegalArgumentException("not a corner view")
        }
    }

    @Rotation
    private fun activeRotationForCorner(corner: View, rtl: Boolean): Int {
        // Each corner will only be visible in a single rotation, based on rtl
        return when (corner) {
            tr -> if (rtl) ROTATION_LANDSCAPE else ROTATION_NONE
            tl -> if (rtl) ROTATION_NONE else ROTATION_SEASCAPE
            br -> if (rtl) ROTATION_UPSIDE_DOWN else ROTATION_LANDSCAPE
            else /* bl */ -> if (rtl) ROTATION_SEASCAPE else ROTATION_UPSIDE_DOWN
        }
    }

    override fun initialize(topLeft: View, topRight: View, bottomLeft: View, bottomRight: View) {
        if (
            this::tl.isInitialized &&
                this::tr.isInitialized &&
                this::bl.isInitialized &&
                this::br.isInitialized
        ) {
            if (tl == topLeft && tr == topRight && bl == bottomLeft && br == bottomRight) {
                return
            }
        }

        tl = topLeft
        tr = topRight
        bl = bottomLeft
        br = bottomRight

        val rtl = configurationController.isLayoutRtl
        val currentRotation = RotationUtils.getExactRotation(tl.context)
        val designatedCornerView = selectDesignatedCorner(currentRotation, rtl)

        val corner = designatedCornerView.corner()

        mainExecutor.execute { animationScheduler.addCallback(systemStatusAnimationCallback) }

        val left = contentInsetsProvider.getStatusBarContentAreaForRotation(ROTATION_SEASCAPE)
        val top = contentInsetsProvider.getStatusBarContentAreaForRotation(ROTATION_NONE)
        val right = contentInsetsProvider.getStatusBarContentAreaForRotation(ROTATION_LANDSCAPE)
        val bottom = contentInsetsProvider.getStatusBarContentAreaForRotation(ROTATION_UPSIDE_DOWN)
        val paddingTop = contentInsetsProvider.getStatusBarPaddingTop()

        synchronized(lock) {
            nextViewState =
                nextViewState.copy(
                    viewInitialized = true,
                    designatedCorner = designatedCornerView,
                    corner = corner,
                    seascapeRect = left,
                    portraitRect = top,
                    landscapeRect = right,
                    upsideDownRect = bottom,
                    paddingTop = paddingTop,
                    layoutRtl = rtl,
                )
        }
    }

    private fun updateStatusBarState() {
        synchronized(lock) { nextViewState = nextViewState.copy(shadeExpanded = isShadeInQs()) }
    }

    /**
     * If we are unlocked with an expanded shade, QS is showing. On keyguard, the shade is always
     * expanded so we use other signals from the panel view controller to know if QS is expanded
     */
    @GuardedBy("lock")
    private fun isShadeInQs(): Boolean {
        return (stateController.isExpanded && stateController.state == SHADE) ||
            (stateController.state == SHADE_LOCKED)
    }

    private fun scheduleUpdate() {
        dlog("scheduleUpdate: ")

        cancelRunnable?.run()
        cancelRunnable = uiExecutor.executeDelayed({ processNextViewState() }, 100)
    }

    @UiThread
    private fun processNextViewState() {
        dlog("processNextViewState: ")

        val newState: ViewState
        synchronized(lock) { newState = nextViewState.copy() }

        resolveState(newState)
    }

    @UiThread
    private fun resolveState(state: ViewState) {
        dlog("resolveState $state")
        if (!state.viewInitialized) {
            dlog("resolveState: view is not initialized. skipping")
            return
        }

        if (state == currentViewState) {
            dlog("resolveState: skipping")
            return
        }

        val designatedCornerChanged = state.designatedCorner != currentViewState.designatedCorner
        val rotationChanged = state.rotation != currentViewState.rotation
        if (rotationChanged || designatedCornerChanged) {
            // A rotation has started, hide the views to avoid flicker
            updateRotations(state.rotation, state.paddingTop)
        }

        if (state.needsLayout(currentViewState)) {
            setCornerSizes(state)
            views.forEach { it.requestLayout() }
        }

        if (designatedCornerChanged) {
            currentViewState.designatedCorner?.contentDescription = null
            state.designatedCorner?.contentDescription = state.contentDescription

            updateDesignatedCorner(state.designatedCorner, state.shouldShowDot())
        } else if (state.contentDescription != currentViewState.contentDescription) {
            state.designatedCorner?.contentDescription = state.contentDescription
        }

        updateDotView(state)

        currentViewState = state
    }

    @UiThread
    override fun updateDotView(state: ViewState) {
        val shouldShow = state.shouldShowDot()
        if (shouldShow != currentViewState.shouldShowDot()) {
            if (shouldShow && state.designatedCorner != null) {
                showDotView(state.designatedCorner, true)
            } else if (!shouldShow && state.designatedCorner != null) {
                hideDotView(state.designatedCorner, true)
            }
        }
    }

    private val systemStatusAnimationCallback: SystemStatusAnimationCallback =
        object : SystemStatusAnimationCallback {
            override fun onSystemStatusAnimationTransitionToPersistentDot(
                contentDescr: String?
            ): Animator? {
                synchronized(lock) {
                    nextViewState =
                        nextViewState.copy(
                            systemPrivacyEventIsActive = true,
                            contentDescription = contentDescr,
                        )
                }

                return null
            }

            override fun onHidePersistentDot(): Animator? {
                synchronized(lock) {
                    nextViewState = nextViewState.copy(systemPrivacyEventIsActive = false)
                }

                return null
            }
        }

    private fun View?.corner(): PrivacyDotCorner? {
        if (this != null) {
            return cornerForView(this)
        }
        return null
    }

    // Returns [left, top, right, bottom] aka [seascape, none, landscape, upside-down]
    private fun getLayoutRects(): List<Rect> {
        val left = contentInsetsProvider.getStatusBarContentAreaForRotation(ROTATION_SEASCAPE)
        val top = contentInsetsProvider.getStatusBarContentAreaForRotation(ROTATION_NONE)
        val right = contentInsetsProvider.getStatusBarContentAreaForRotation(ROTATION_LANDSCAPE)
        val bottom = contentInsetsProvider.getStatusBarContentAreaForRotation(ROTATION_UPSIDE_DOWN)

        return listOf(left, top, right, bottom)
    }

    private fun setNewLayoutRects() {
        val rects = getLayoutRects()

        synchronized(lock) {
            nextViewState =
                nextViewState.copy(
                    seascapeRect = rects[0],
                    portraitRect = rects[1],
                    landscapeRect = rects[2],
                    upsideDownRect = rects[3],
                )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            scope: CoroutineScope,
            configurationController: ConfigurationController,
            contentInsetsProvider: StatusBarContentInsetsProvider,
        ): PrivacyDotViewControllerImpl
    }
}

private fun dlog(s: String) {
    if (DEBUG) {
        Log.d(TAG, s)
    }
}

private fun vlog(s: String) {
    if (DEBUG_VERBOSE) {
        Log.d(TAG, s)
    }
}

private const val DURATION = 160L
private const val TAG = "PrivacyDotViewController"
private const val DEBUG = false
private const val DEBUG_VERBOSE = false

data class ViewState(
    val viewInitialized: Boolean = false,
    val systemPrivacyEventIsActive: Boolean = false,
    val shadeExpanded: Boolean = false,
    val qsExpanded: Boolean = false,
    val portraitRect: Rect? = null,
    val landscapeRect: Rect? = null,
    val upsideDownRect: Rect? = null,
    val seascapeRect: Rect? = null,
    val layoutRtl: Boolean = false,
    val rotation: Int = 0,
    val paddingTop: Int = 0,
    val corner: PrivacyDotCorner? = null,
    val designatedCorner: View? = null,
    val contentDescription: String? = null,
) {
    fun shouldShowDot(): Boolean {
        return systemPrivacyEventIsActive && !shadeExpanded && !qsExpanded
    }

    fun needsLayout(other: ViewState): Boolean {
        return rotation != other.rotation ||
            layoutRtl != other.layoutRtl ||
            portraitRect != other.portraitRect ||
            landscapeRect != other.landscapeRect ||
            upsideDownRect != other.upsideDownRect ||
            seascapeRect != other.seascapeRect
    }

    fun contentRectForRotation(@Rotation rot: Int): Rect {
        return when (rot) {
            ROTATION_NONE -> portraitRect!!
            ROTATION_LANDSCAPE -> landscapeRect!!
            ROTATION_UPSIDE_DOWN -> upsideDownRect!!
            ROTATION_SEASCAPE -> seascapeRect!!
            else -> throw IllegalArgumentException("not a rotation ($rot)")
        }
    }
}

@Module
object PrivacyDotViewControllerModule {

    @Provides
    @SysUISingleton
    fun controller(
        factory: PrivacyDotViewControllerImpl.Factory,
        @Application scope: CoroutineScope,
        configurationController: ConfigurationController,
        contentInsetsProviderStore: StatusBarContentInsetsProviderStore,
    ): PrivacyDotViewController {
        return factory.create(
            scope,
            configurationController,
            contentInsetsProviderStore.defaultDisplay,
        )
    }
}
