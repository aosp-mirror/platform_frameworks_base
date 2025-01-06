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

package com.android.wm.shell.windowdecor.tiling

import android.content.Context
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Binder
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_SLIPPERY
import android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
import android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER
import android.view.WindowlessWindowManager
import com.android.wm.shell.R
import com.android.wm.shell.common.SyncTransactionQueue
import java.util.function.Supplier

/**
 * a [WindowlessWindowManaer] responsible for hosting the [TilingDividerView] on the display root
 * when two tasks are tiled on left and right to resize them simultaneously.
 */
class DesktopTilingDividerWindowManager(
    config: Configuration,
    private val windowName: String,
    private val context: Context,
    private val leash: SurfaceControl,
    private val syncQueue: SyncTransactionQueue,
    private val transitionHandler: DesktopTilingWindowDecoration,
    private val transactionSupplier: Supplier<SurfaceControl.Transaction>,
    private var dividerBounds: Rect,
    private val displayContext: Context,
) : WindowlessWindowManager(config, leash, null), DividerMoveCallback, View.OnLayoutChangeListener {
    private lateinit var viewHost: SurfaceControlViewHost
    private var tilingDividerView: TilingDividerView? = null
    private var dividerShown = false
    private var handleRegionSize: Size =
        Size(
            context.resources.getDimensionPixelSize(R.dimen.split_divider_handle_region_width),
            context.resources.getDimensionPixelSize(R.dimen.split_divider_handle_region_height),
        )
    private var setTouchRegion = true
    private val maxRoundedCornerRadius = getMaxRoundedCornerRadius()

    /**
     * Gets bounds of divider window with screen based coordinate on the param Rect.
     *
     * @param rect bounds for the [TilingDividerView]
     */
    fun getDividerBounds(rect: Rect) {
        rect.set(dividerBounds)
    }

    /**
     * Sets the touch region for the SurfaceControlViewHost.
     *
     * The region includes the area around the handle (for accessibility), the divider itself and
     * the rounded corners (to prevent click reaching windows behind).
     */
    fun setTouchRegion(handle: Rect, divider: Rect, cornerRadius: Float) {
        val path = Path()
        path.fillType = Path.FillType.WINDING
        // The UI starts on the top-left corner, the region will be:
        //
        //      cornerLeft     cornerRight
        // c1Top        +--------+
        //              |corners |
        // c1Bottom     +--+  +--+
        //                 |  |
        //       handleLeft|  |  handleRight
        // handleTop  +----+  +----+
        //            |  handle    |
        // handleBot  +----+  +----+
        //                 |  |
        //                 |  |
        // c2Top        +--+  +--+
        //              |corners |
        // c2Bottom     +--------+
        val cornerLeft = 0f
        val centerX = cornerRadius + divider.width() / 2f
        val centerY = divider.height()
        val cornerRight = divider.width() + 2 * cornerRadius
        val handleLeft = centerX - handle.width() / 2f
        val handleRight = handleLeft + handle.width()
        val dividerLeft = centerY - divider.width() / 2f
        val dividerRight = dividerLeft + divider.width()

        val c1Top = 0f
        val c1Bottom = cornerRadius
        val handleTop = centerY - handle.height() / 2f
        val handleBottom = handleTop + handle.height()
        val c2Top = divider.height() - cornerRadius
        val c2Bottom = divider.height().toFloat()

        // Top corners
        path.addRect(cornerLeft, c1Top, cornerRight, c1Bottom, Path.Direction.CCW)
        // Bottom corners
        path.addRect(cornerLeft, c1Top, cornerRight, c2Bottom, Path.Direction.CCW)
        // Handle
        path.addRect(handleLeft, handleTop, handleRight, handleBottom, Path.Direction.CCW)
        // Divider
        path.addRect(dividerLeft, c2Top, dividerRight, c2Bottom, Path.Direction.CCW)

        val clip = Rect(handleLeft.toInt(), c1Top.toInt(), handleRight.toInt(), c2Bottom.toInt())

        val region = Region()
        region.setPath(path, Region(clip))

        setTouchRegion(viewHost.windowToken.asBinder(), region)
    }

    /**
     * Builds a view host upon tiling two tasks left and right, and shows the divider view in the
     * middle of the screen between both tasks.
     *
     * @param relativeLeash the task leash that the TilingDividerView should be shown on top of.
     */
    fun generateViewHost(relativeLeash: SurfaceControl) {
        val t = transactionSupplier.get()
        val surfaceControlViewHost =
            SurfaceControlViewHost(context, context.display, this, "DesktopTilingManager")
        val dividerView =
            LayoutInflater.from(context).inflate(R.layout.tiling_split_divider, /* root= */ null)
                as TilingDividerView
        val lp = getWindowManagerParams()
        surfaceControlViewHost.setView(dividerView, lp)
        val tmpDividerBounds = Rect()
        getDividerBounds(tmpDividerBounds)
        dividerView.setup(this, tmpDividerBounds, handleRegionSize)
        t.setRelativeLayer(leash, relativeLeash, 1)
            .setPosition(
                leash,
                dividerBounds.left.toFloat() - maxRoundedCornerRadius,
                dividerBounds.top.toFloat(),
            )
            .show(leash)
        syncQueue.runInSync { transaction ->
            transaction.merge(t)
            t.close()
        }
        dividerShown = true
        viewHost = surfaceControlViewHost
        dividerView.addOnLayoutChangeListener(this)
        tilingDividerView = dividerView
        updateTouchRegion()
    }

    /** Hides the divider bar. */
    fun hideDividerBar() {
        if (!dividerShown) {
            return
        }
        val t = transactionSupplier.get()
        t.hide(leash)
        t.apply()
        dividerShown = false
    }

    /** Shows the divider bar. */
    fun showDividerBar() {
        if (dividerShown) return
        val t = transactionSupplier.get()
        t.show(leash)
        t.apply()
        dividerShown = true
    }

    /**
     * When the tiled task on top changes, the divider bar's Z access should change to be on top of
     * the latest focused task.
     */
    fun onRelativeLeashChanged(relativeLeash: SurfaceControl, t: SurfaceControl.Transaction) {
        t.setRelativeLayer(leash, relativeLeash, 1)
    }

    override fun onDividerMoveStart(pos: Int, motionEvent: MotionEvent) {
        setSlippery(false)
        transitionHandler.onDividerHandleDragStart(motionEvent)
    }

    /**
     * Moves the divider view to a new position after touch, gets called from the
     * [TilingDividerView] onTouch function.
     */
    override fun onDividerMove(pos: Int): Boolean {
        val t = transactionSupplier.get()
        t.setPosition(leash, pos.toFloat() - maxRoundedCornerRadius, dividerBounds.top.toFloat())
        val dividerWidth = dividerBounds.width()
        dividerBounds.set(pos, dividerBounds.top, pos + dividerWidth, dividerBounds.bottom)
        return transitionHandler.onDividerHandleMoved(dividerBounds, t)
    }

    /**
     * Notifies the transition handler of tiling operations ending, which might result in resizing
     * WindowContainerTransactions if the sizes of the tiled tasks changed.
     */
    override fun onDividerMovedEnd(pos: Int, motionEvent: MotionEvent) {
        setSlippery(true)
        val t = transactionSupplier.get()
        t.setPosition(leash, pos.toFloat() - maxRoundedCornerRadius, dividerBounds.top.toFloat())
        val dividerWidth = dividerBounds.width()
        dividerBounds.set(pos, dividerBounds.top, pos + dividerWidth, dividerBounds.bottom)
        transitionHandler.onDividerHandleDragEnd(dividerBounds, t, motionEvent)
    }

    private fun getWindowManagerParams(): WindowManager.LayoutParams {
        val lp =
            WindowManager.LayoutParams(
                /* w= */ dividerBounds.width() + 2 * maxRoundedCornerRadius,
                /* h= */ dividerBounds.height(),
                TYPE_DOCK_DIVIDER,
                FLAG_NOT_FOCUSABLE or
                    FLAG_NOT_TOUCH_MODAL or
                    FLAG_WATCH_OUTSIDE_TOUCH or
                    FLAG_SPLIT_TOUCH or
                    FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT,
            )
        lp.token = Binder()
        lp.title = windowName
        lp.privateFlags =
            lp.privateFlags or (PRIVATE_FLAG_NO_MOVE_ANIMATION or PRIVATE_FLAG_TRUSTED_OVERLAY)
        return lp
    }

    /**
     * Releases the surface control of the current [TilingDividerView] and tear down the view
     * hierarchy.y.
     */
    fun release() {
        tilingDividerView = null
        viewHost.release()
        transactionSupplier.get().hide(leash).remove(leash).apply()
    }

    override fun onLayoutChange(
        v: View?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int,
    ) {
        if (!setTouchRegion) return

        updateTouchRegion()
        setTouchRegion = false
    }

    private fun updateTouchRegion() {
        val startX = -handleRegionSize.width / 2
        val handle = Rect(startX, 0, startX + handleRegionSize.width, dividerBounds.height())
        setTouchRegion(handle, dividerBounds, maxRoundedCornerRadius.toFloat())
    }

    private fun setSlippery(slippery: Boolean) {
        val lp = tilingDividerView?.layoutParams as WindowManager.LayoutParams
        val isSlippery = (lp.flags and FLAG_SLIPPERY) != 0
        if (isSlippery == slippery) return

        if (slippery) {
            lp.flags = lp.flags or FLAG_SLIPPERY
        } else {
            lp.flags = lp.flags and FLAG_SLIPPERY.inv()
        }
        viewHost.relayout(lp)
    }

    private fun getMaxRoundedCornerRadius(): Int {
        val display = displayContext.display
        return listOf(
                RoundedCorner.POSITION_TOP_LEFT,
                RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT,
            )
            .maxOf { position -> display.getRoundedCorner(position)?.getRadius() ?: 0 }
    }
}
