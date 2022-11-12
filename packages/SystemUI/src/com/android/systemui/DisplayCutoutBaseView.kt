/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.Dimension
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.AttributeSet
import android.view.Display
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.Surface
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.RegionInterceptingFrameLayout.RegionInterceptableView
import com.android.systemui.animation.Interpolators

/**
 *  A class that handles common actions of display cutout view.
 *  - Draws cutouts.
 *  - Handles camera protection.
 *  - Intercepts touches on cutout areas.
 */
open class DisplayCutoutBaseView : View, RegionInterceptableView {

    private var shouldDrawCutout: Boolean = DisplayCutout.getFillBuiltInDisplayCutout(
        context.resources, context.display?.uniqueId
    )
    private var displayUniqueId: String? = null
    private var displayMode: Display.Mode? = null
    protected val location = IntArray(2)
    protected var displayRotation = 0

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @JvmField val displayInfo = DisplayInfo()
    @JvmField protected var pendingConfigChange = false
    @JvmField protected val paint = Paint()
    @JvmField protected val cutoutPath = Path()

    @JvmField protected var showProtection = false
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @JvmField val protectionRect: RectF = RectF()
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @JvmField val protectionPath: Path = Path()
    private val protectionRectOrig: RectF = RectF()
    private val protectionPathOrig: Path = Path()
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    var cameraProtectionProgress: Float = HIDDEN_CAMERA_PROTECTION_SCALE
    private var cameraProtectionAnimator: ValueAnimator? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displayUniqueId = context.display?.uniqueId
        updateCutout()
        updateProtectionBoundingPath()
        onUpdate()
    }

    fun updateConfiguration(newDisplayUniqueId: String?) {
        val info = DisplayInfo()
        context.display?.getDisplayInfo(info)
        val oldMode: Display.Mode? = displayMode
        displayMode = info.mode

        updateDisplayUniqueId(info.uniqueId)

        // Skip if display mode or cutout hasn't changed.
        if (!displayModeChanged(oldMode, displayMode) &&
                displayInfo.displayCutout == info.displayCutout &&
                displayRotation == info.rotation) {
            return
        }
        if (newDisplayUniqueId == info.uniqueId) {
            displayRotation = info.rotation
            updateCutout()
            updateProtectionBoundingPath()
            onUpdate()
        }
    }

    open fun updateDisplayUniqueId(newDisplayUniqueId: String?) {
        if (displayUniqueId != newDisplayUniqueId) {
            displayUniqueId = newDisplayUniqueId
            shouldDrawCutout = DisplayCutout.getFillBuiltInDisplayCutout(
                    context.resources, displayUniqueId
            )
            invalidate()
        }
    }

    open fun updateRotation(rotation: Int) {
        displayRotation = rotation
        updateCutout()
        updateProtectionBoundingPath()
        onUpdate()
    }

    // Called after the cutout and protection bounding path change. Subclasses
    // should make any changes that need to happen based on the change.
    open fun onUpdate() = Unit

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!shouldDrawCutout) {
            return
        }
        canvas.save()
        getLocationOnScreen(location)
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())

        drawCutouts(canvas)
        drawCutoutProtection(canvas)
        canvas.restore()
    }

    override fun shouldInterceptTouch(): Boolean {
        return displayInfo.displayCutout != null && visibility == VISIBLE && shouldDrawCutout
    }

    override fun getInterceptRegion(): Region? {
        displayInfo.displayCutout ?: return null

        val cutoutBounds: Region = rectsToRegion(displayInfo.displayCutout?.boundingRects)
        // Transform to window's coordinate space
        rootView.getLocationOnScreen(location)
        cutoutBounds.translate(-location[0], -location[1])

        // Intersect with window's frame
        cutoutBounds.op(
            rootView.left, rootView.top, rootView.right, rootView.bottom, Region.Op.INTERSECT
        )
        return cutoutBounds
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    open fun updateCutout() {
        if (pendingConfigChange) {
            return
        }
        cutoutPath.reset()
        context.display?.getDisplayInfo(displayInfo)
        displayInfo.displayCutout?.cutoutPath?.let { path -> cutoutPath.set(path) }
        invalidate()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    open fun drawCutouts(canvas: Canvas) {
        displayInfo.displayCutout?.cutoutPath ?: return
        canvas.drawPath(cutoutPath, paint)
    }

    protected open fun drawCutoutProtection(canvas: Canvas) {
        if (cameraProtectionProgress > HIDDEN_CAMERA_PROTECTION_SCALE &&
            !protectionRect.isEmpty
        ) {
            canvas.scale(
                cameraProtectionProgress, cameraProtectionProgress, protectionRect.centerX(),
                protectionRect.centerY()
            )
            canvas.drawPath(protectionPath, paint)
        }
    }

    /**
     * Converts a set of [Rect]s into a [Region]
     */
    fun rectsToRegion(rects: List<Rect?>?): Region {
        val result = Region.obtain()
        if (rects != null) {
            for (r in rects) {
                if (r != null && !r.isEmpty) {
                    result.op(r, Region.Op.UNION)
                }
            }
        }
        return result
    }

    open fun enableShowProtection(show: Boolean) {
        if (showProtection == show) {
            return
        }
        showProtection = show
        updateProtectionBoundingPath()
        // Delay the relayout until the end of the animation when hiding the cutout,
        // otherwise we'd clip it.
        if (showProtection) {
            requestLayout()
        }
        cameraProtectionAnimator?.cancel()
        cameraProtectionAnimator = ValueAnimator.ofFloat(
            cameraProtectionProgress,
            if (showProtection) 1.0f else HIDDEN_CAMERA_PROTECTION_SCALE
        ).setDuration(750)
        cameraProtectionAnimator?.interpolator = Interpolators.DECELERATE_QUINT
        cameraProtectionAnimator?.addUpdateListener(
            ValueAnimator.AnimatorUpdateListener { animation: ValueAnimator ->
                cameraProtectionProgress = animation.animatedValue as Float
                invalidate()
            }
        )
        cameraProtectionAnimator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                cameraProtectionAnimator = null
                if (!showProtection) {
                    requestLayout()
                }
            }
        })
        cameraProtectionAnimator?.start()
    }

    open fun setProtection(path: Path, pathBounds: Rect) {
        protectionPathOrig.reset()
        protectionPathOrig.set(path)
        protectionPath.reset()
        protectionRectOrig.setEmpty()
        protectionRectOrig.set(pathBounds)
        protectionRect.setEmpty()
    }

    protected open fun updateProtectionBoundingPath() {
        if (pendingConfigChange) {
            return
        }
        val m = Matrix()
        // Apply display ratio.
        val physicalPixelDisplaySizeRatio = getPhysicalPixelDisplaySizeRatio()
        m.postScale(physicalPixelDisplaySizeRatio, physicalPixelDisplaySizeRatio)

        // Apply rotation.
        val lw: Int = displayInfo.logicalWidth
        val lh: Int = displayInfo.logicalHeight
        val flipped = (
            displayInfo.rotation == Surface.ROTATION_90 ||
                displayInfo.rotation == Surface.ROTATION_270
            )
        val dw = if (flipped) lh else lw
        val dh = if (flipped) lw else lh
        transformPhysicalToLogicalCoordinates(displayInfo.rotation, dw, dh, m)

        if (!protectionPathOrig.isEmpty) {
            // Reset the protection path so we don't aggregate rotations
            protectionPath.set(protectionPathOrig)
            protectionPath.transform(m)
            m.mapRect(protectionRect, protectionRectOrig)
        }
    }

    @VisibleForTesting
    open fun getPhysicalPixelDisplaySizeRatio(): Float {
        displayInfo.displayCutout?.let {
            return it.cutoutPathParserInfo.physicalPixelDisplaySizeRatio
        }
        return 1f
    }

    private fun displayModeChanged(oldMode: Display.Mode?, newMode: Display.Mode?): Boolean {
        if (oldMode == null) {
            return true
        }

        // We purposely ignore refresh rate and id changes here, because we don't need to
        // invalidate for those, and they can trigger the refresh rate to increase
        return oldMode?.physicalHeight != newMode?.physicalHeight ||
            oldMode?.physicalWidth != newMode?.physicalWidth
    }

    companion object {
        const val HIDDEN_CAMERA_PROTECTION_SCALE = 0.5f

        @JvmStatic protected fun transformPhysicalToLogicalCoordinates(
            @Surface.Rotation rotation: Int,
            @Dimension physicalWidth: Int,
            @Dimension physicalHeight: Int,
            out: Matrix
        ) {
            when (rotation) {
                Surface.ROTATION_0 -> return
                Surface.ROTATION_90 -> {
                    out.postRotate(270f)
                    out.postTranslate(0f, physicalWidth.toFloat())
                }
                Surface.ROTATION_180 -> {
                    out.postRotate(180f)
                    out.postTranslate(physicalWidth.toFloat(), physicalHeight.toFloat())
                }
                Surface.ROTATION_270 -> {
                    out.postRotate(90f)
                    out.postTranslate(physicalHeight.toFloat(), 0f)
                }
                else -> throw IllegalArgumentException("Unknown rotation: $rotation")
            }
        }
    }
}
