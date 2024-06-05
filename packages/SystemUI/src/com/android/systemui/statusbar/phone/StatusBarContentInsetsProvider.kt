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

import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.graphics.Rect
import android.util.LruCache
import android.util.Pair
import android.view.DisplayCutout
import androidx.annotation.VisibleForTesting
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN
import com.android.systemui.util.leak.RotationUtils.Rotation
import com.android.systemui.util.leak.RotationUtils.getExactRotation
import com.android.systemui.util.leak.RotationUtils.getResourcesForRotation
import com.android.systemui.util.traceSection

import java.io.PrintWriter
import java.lang.Math.max
import javax.inject.Inject

/**
 * Encapsulates logic that can solve for the left/right insets required for the status bar contents.
 * Takes into account:
 *  1. rounded_corner_content_padding
 *  2. status_bar_padding_start, status_bar_padding_end
 *  2. display cutout insets from left or right
 *  3. waterfall insets
 *
 *
 *  Importantly, these functions can determine status bar content left/right insets for any rotation
 *  before having done a layout pass in that rotation.
 *
 *  NOTE: This class is not threadsafe
 */
@SysUISingleton
class StatusBarContentInsetsProvider @Inject constructor(
    val context: Context,
    val configurationController: ConfigurationController,
    val dumpManager: DumpManager
) : CallbackController<StatusBarContentInsetsChangedListener>,
        ConfigurationController.ConfigurationListener,
        Dumpable {

    // Limit cache size as potentially we may connect large number of displays
    // (e.g. network displays)
    private val insetsCache = LruCache<CacheKey, Rect>(MAX_CACHE_SIZE)
    private val listeners = mutableSetOf<StatusBarContentInsetsChangedListener>()
    private val isPrivacyDotEnabled: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        context.resources.getBoolean(R.bool.config_enablePrivacyDot)
    }

    init {
        configurationController.addCallback(this)
        dumpManager.registerDumpable(TAG, this)
    }

    override fun addCallback(listener: StatusBarContentInsetsChangedListener) {
        listeners.add(listener)
    }

    override fun removeCallback(listener: StatusBarContentInsetsChangedListener) {
        listeners.remove(listener)
    }

    override fun onDensityOrFontScaleChanged() {
        clearCachedInsets()
    }

    override fun onThemeChanged() {
        clearCachedInsets()
    }

    override fun onMaxBoundsChanged() {
        notifyInsetsChanged()
    }

    private fun clearCachedInsets() {
        insetsCache.evictAll()
        notifyInsetsChanged()
    }

    private fun notifyInsetsChanged() {
        listeners.forEach {
            it.onStatusBarContentInsetsChanged()
        }
    }

    /**
     * Some views may need to care about whether or not the current top display cutout is located
     * in the corner rather than somewhere in the center. In the case of a corner cutout, the
     * status bar area is contiguous.
     */
    fun currentRotationHasCornerCutout(): Boolean {
        val cutout = checkNotNull(context.display).cutout ?: return false
        val topBounds = cutout.boundingRectTop

        val point = Point()
        checkNotNull(context.display).getRealSize(point)

        return topBounds.left <= 0 || topBounds.right >= point.x
    }

    /**
     * Calculates the maximum bounding rectangle for the privacy chip animation + ongoing privacy
     * dot in the coordinates relative to the given rotation.
     *
     * @param rotation the rotation for which the bounds are required. This is an absolute value
     *      (i.e., ROTATION_NONE will always return the same bounds regardless of the context
     *      from which this method is called)
     */
    fun getBoundingRectForPrivacyChipForRotation(@Rotation rotation: Int,
                                                 displayCutout: DisplayCutout?): Rect {
        val key = getCacheKey(rotation, displayCutout)
        var insets = insetsCache[key]
        if (insets == null) {
            insets = getStatusBarContentAreaForRotation(rotation)
        }

        val rotatedResources = getResourcesForRotation(rotation, context)

        val dotWidth = rotatedResources.getDimensionPixelSize(R.dimen.ongoing_appops_dot_diameter)
        val chipWidth = rotatedResources.getDimensionPixelSize(
                R.dimen.ongoing_appops_chip_max_width)

        val isRtl = configurationController.isLayoutRtl
        return getPrivacyChipBoundingRectForInsets(insets, dotWidth, chipWidth, isRtl)
    }

    /**
     * Calculate the distance from the left and right edges of the screen to the status bar
     * content area. This differs from the content area rects in that these values can be used
     * directly as padding.
     *
     * @param rotation the target rotation for which to calculate insets
     */
    fun getStatusBarContentInsetsForRotation(@Rotation rotation: Int): Pair<Int, Int> =
        traceSection(tag = "StatusBarContentInsetsProvider.getStatusBarContentInsetsForRotation") {
            val displayCutout = checkNotNull(context.display).cutout
            val key = getCacheKey(rotation, displayCutout)

            val screenBounds = context.resources.configuration.windowConfiguration.maxBounds
            val point = Point(screenBounds.width(), screenBounds.height())

            // Target rotation can be a different orientation than the current device rotation
            point.orientToRotZero(getExactRotation(context))
            val width = point.logicalWidth(rotation)

            val area = insetsCache[key] ?: getAndSetCalculatedAreaForRotation(
                rotation, displayCutout, getResourcesForRotation(rotation, context), key)

            Pair(area.left, width - area.right)
        }

    /**
     * Calculate the left and right insets for the status bar content in the device's current
     * rotation
     * @see getStatusBarContentAreaForRotation
     */
    fun getStatusBarContentInsetsForCurrentRotation(): Pair<Int, Int> {
        return getStatusBarContentInsetsForRotation(getExactRotation(context))
    }

    /**
     * Calculates the area of the status bar contents invariant of  the current device rotation,
     * in the target rotation's coordinates
     *
     * @param rotation the rotation for which the bounds are required. This is an absolute value
     *      (i.e., ROTATION_NONE will always return the same bounds regardless of the context
     *      from which this method is called)
     */
    @JvmOverloads
    fun getStatusBarContentAreaForRotation(
        @Rotation rotation: Int
    ): Rect {
        val displayCutout = checkNotNull(context.display).cutout
        val key = getCacheKey(rotation, displayCutout)
        return insetsCache[key] ?: getAndSetCalculatedAreaForRotation(
                rotation, displayCutout, getResourcesForRotation(rotation, context), key)
    }

    /**
     * Get the status bar content area for the given rotation, in absolute bounds
     */
    fun getStatusBarContentAreaForCurrentRotation(): Rect {
        val rotation = getExactRotation(context)
        return getStatusBarContentAreaForRotation(rotation)
    }

    private fun getAndSetCalculatedAreaForRotation(
        @Rotation targetRotation: Int,
        displayCutout: DisplayCutout?,
        rotatedResources: Resources,
        key: CacheKey
    ): Rect {
        return getCalculatedAreaForRotation(displayCutout, targetRotation, rotatedResources)
                .also {
                    insetsCache.put(key, it)
                }
    }

    private fun getCalculatedAreaForRotation(
        displayCutout: DisplayCutout?,
        @Rotation targetRotation: Int,
        rotatedResources: Resources
    ): Rect {
        val currentRotation = getExactRotation(context)

        val roundedCornerPadding = rotatedResources
                .getDimensionPixelSize(R.dimen.rounded_corner_content_padding)
        val minDotPadding = if (isPrivacyDotEnabled)
                rotatedResources.getDimensionPixelSize(R.dimen.ongoing_appops_dot_min_padding)
            else 0
        val dotWidth = if (isPrivacyDotEnabled)
                rotatedResources.getDimensionPixelSize(R.dimen.ongoing_appops_dot_diameter)
            else 0

        val minLeft: Int
        val minRight: Int
        if (configurationController.isLayoutRtl) {
            minLeft = max(minDotPadding, roundedCornerPadding)
            minRight = roundedCornerPadding
        } else {
            minLeft = roundedCornerPadding
            minRight = max(minDotPadding, roundedCornerPadding)
        }

        return calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                displayCutout,
                context.resources.configuration.windowConfiguration.maxBounds,
                SystemBarUtils.getStatusBarHeightForRotation(context, targetRotation),
                minLeft,
                minRight,
                configurationController.isLayoutRtl,
                dotWidth)
    }

    fun getStatusBarPaddingTop(@Rotation rotation: Int? = null): Int {
        val res = rotation?.let { it -> getResourcesForRotation(it, context) } ?: context.resources
        return res.getDimensionPixelSize(R.dimen.status_bar_padding_top)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        insetsCache.snapshot().forEach { (key, rect) ->
            pw.println("$key -> $rect")
        }
        pw.println(insetsCache)
    }

    private fun getCacheKey(
            @Rotation rotation: Int,
            displayCutout: DisplayCutout?): CacheKey =
        CacheKey(
            rotation = rotation,
            displaySize = Rect(context.resources.configuration.windowConfiguration.maxBounds),
            displayCutout = displayCutout
        )

    private data class CacheKey(
        @Rotation val rotation: Int,
        val displaySize: Rect,
        val displayCutout: DisplayCutout?
    )
}

interface StatusBarContentInsetsChangedListener {
    fun onStatusBarContentInsetsChanged()
}

private const val TAG = "StatusBarInsetsProvider"
private const val MAX_CACHE_SIZE = 16

private fun getRotationZeroDisplayBounds(bounds: Rect, @Rotation exactRotation: Int): Rect {
    if (exactRotation == ROTATION_NONE || exactRotation == ROTATION_UPSIDE_DOWN) {
        return bounds
    }

    // bounds are horizontal, swap height and width
    return Rect(0, 0, bounds.bottom, bounds.right)
}

@VisibleForTesting
fun getPrivacyChipBoundingRectForInsets(
    contentRect: Rect,
    dotWidth: Int,
    chipWidth: Int,
    isRtl: Boolean
): Rect {
    return if (isRtl) {
        Rect(contentRect.left - dotWidth,
                contentRect.top,
                contentRect.left + chipWidth,
                contentRect.bottom)
    } else {
        Rect(contentRect.right - chipWidth,
                contentRect.top,
                contentRect.right + dotWidth,
                contentRect.bottom)
    }
}

/**
 * Calculates the exact left and right positions for the status bar contents for the given
 * rotation
 *
 * @param currentRotation current device rotation
 * @param targetRotation rotation for which to calculate the status bar content rect
 * @param displayCutout [DisplayCutout] for the current display. possibly null
 * @param maxBounds the display bounds in our current rotation
 * @param statusBarHeight height of the status bar for the target rotation
 * @param minLeft the minimum padding to enforce on the left
 * @param minRight the minimum padding to enforce on the right
 * @param isRtl current layout direction is Right-To-Left or not
 * @param dotWidth privacy dot image width (0 if privacy dot is disabled)
 *
 * @see [RotationUtils#getResourcesForRotation]
 */
fun calculateInsetsForRotationWithRotatedResources(
    @Rotation currentRotation: Int,
    @Rotation targetRotation: Int,
    displayCutout: DisplayCutout?,
    maxBounds: Rect,
    statusBarHeight: Int,
    minLeft: Int,
    minRight: Int,
    isRtl: Boolean,
    dotWidth: Int
): Rect {
    /*
    TODO: Check if this is ever used for devices with no rounded corners
    val left = if (isRtl) paddingEnd else paddingStart
    val right = if (isRtl) paddingStart else paddingEnd
     */

    val rotZeroBounds = getRotationZeroDisplayBounds(maxBounds, currentRotation)

    val sbLeftRight = getStatusBarLeftRight(
            displayCutout,
            statusBarHeight,
            rotZeroBounds.right,
            rotZeroBounds.bottom,
            maxBounds.width(),
            maxBounds.height(),
            minLeft,
            minRight,
            isRtl,
            dotWidth,
            targetRotation,
            currentRotation)

    return sbLeftRight
}

/**
 * Calculate the insets needed from the left and right edges for the given rotation.
 *
 * @param displayCutout Device display cutout
 * @param sbHeight appropriate status bar height for this rotation
 * @param width display width calculated for ROTATION_NONE
 * @param height display height calculated for ROTATION_NONE
 * @param cWidth display width in our current rotation
 * @param cHeight display height in our current rotation
 * @param minLeft the minimum padding to enforce on the left
 * @param minRight the minimum padding to enforce on the right
 * @param isRtl current layout direction is Right-To-Left or not
 * @param dotWidth privacy dot image width (0 if privacy dot is disabled)
 * @param targetRotation the rotation for which to calculate margins
 * @param currentRotation the rotation from which the display cutout was generated
 *
 * @return a Rect which exactly calculates the Status Bar's content rect relative to the target
 * rotation
 */
private fun getStatusBarLeftRight(
    displayCutout: DisplayCutout?,
    sbHeight: Int,
    width: Int,
    height: Int,
    cWidth: Int,
    cHeight: Int,
    minLeft: Int,
    minRight: Int,
    isRtl: Boolean,
    dotWidth: Int,
    @Rotation targetRotation: Int,
    @Rotation currentRotation: Int
): Rect {

    val logicalDisplayWidth = if (targetRotation.isHorizontal()) height else width

    val cutoutRects = displayCutout?.boundingRects
    if (cutoutRects == null || cutoutRects.isEmpty()) {
        return Rect(minLeft,
                0,
                logicalDisplayWidth - minRight,
                sbHeight)
    }

    val relativeRotation = if (currentRotation - targetRotation < 0) {
        currentRotation - targetRotation + 4
    } else {
        currentRotation - targetRotation
    }

    // Size of the status bar window for the given rotation relative to our exact rotation
    val sbRect = sbRect(relativeRotation, sbHeight, Pair(cWidth, cHeight))

    var leftMargin = minLeft
    var rightMargin = minRight
    for (cutoutRect in cutoutRects) {
        // There is at most one non-functional area per short edge of the device. So if the status
        // bar doesn't share a short edge with the cutout, we can ignore its insets because there
        // will be no letter-boxing to worry about
        if (!shareShortEdge(sbRect, cutoutRect, cWidth, cHeight)) {
            continue
        }

        if (cutoutRect.touchesLeftEdge(relativeRotation, cWidth, cHeight)) {
            var logicalWidth = cutoutRect.logicalWidth(relativeRotation)
            if (isRtl) logicalWidth += dotWidth
            leftMargin = max(logicalWidth, leftMargin)
        } else if (cutoutRect.touchesRightEdge(relativeRotation, cWidth, cHeight)) {
            var logicalWidth = cutoutRect.logicalWidth(relativeRotation)
            if (!isRtl) logicalWidth += dotWidth
            rightMargin = max(rightMargin, logicalWidth)
        }
        // TODO(b/203626889): Fix the scenario when config_mainBuiltInDisplayCutoutRectApproximation
        //                    is very close to but not directly touch edges.
    }

    return Rect(leftMargin, 0, logicalDisplayWidth - rightMargin, sbHeight)
}

private fun sbRect(
    @Rotation relativeRotation: Int,
    sbHeight: Int,
    displaySize: Pair<Int, Int>
): Rect {
    val w = displaySize.first
    val h = displaySize.second
    return when (relativeRotation) {
        ROTATION_NONE -> Rect(0, 0, w, sbHeight)
        ROTATION_LANDSCAPE -> Rect(0, 0, sbHeight, h)
        ROTATION_UPSIDE_DOWN -> Rect(0, h - sbHeight, w, h)
        else -> Rect(w - sbHeight, 0, w, h)
    }
}

private fun shareShortEdge(
    sbRect: Rect,
    cutoutRect: Rect,
    currentWidth: Int,
    currentHeight: Int
): Boolean {
    if (currentWidth < currentHeight) {
        // Check top/bottom edges by extending the width of the display cutout rect and checking
        // for intersections
        return sbRect.intersects(0, cutoutRect.top, currentWidth, cutoutRect.bottom)
    } else if (currentWidth > currentHeight) {
        // Short edge is the height, extend that one this time
        return sbRect.intersects(cutoutRect.left, 0, cutoutRect.right, currentHeight)
    }

    return false
}

private fun Rect.touchesRightEdge(@Rotation rot: Int, width: Int, height: Int): Boolean {
    return when (rot) {
        ROTATION_NONE -> right >= width
        ROTATION_LANDSCAPE -> top <= 0
        ROTATION_UPSIDE_DOWN -> left <= 0
        else /* SEASCAPE */ -> bottom >= height
    }
}

private fun Rect.touchesLeftEdge(@Rotation rot: Int, width: Int, height: Int): Boolean {
    return when (rot) {
        ROTATION_NONE -> left <= 0
        ROTATION_LANDSCAPE -> bottom >= height
        ROTATION_UPSIDE_DOWN -> right >= width
        else /* SEASCAPE */ -> top <= 0
    }
}

private fun Rect.logicalTop(@Rotation rot: Int): Int {
    return when (rot) {
        ROTATION_NONE -> top
        ROTATION_LANDSCAPE -> left
        ROTATION_UPSIDE_DOWN -> bottom
        else /* SEASCAPE */ -> right
    }
}

private fun Rect.logicalRight(@Rotation rot: Int): Int {
    return when (rot) {
        ROTATION_NONE -> right
        ROTATION_LANDSCAPE -> top
        ROTATION_UPSIDE_DOWN -> left
        else /* SEASCAPE */ -> bottom
    }
}

private fun Rect.logicalLeft(@Rotation rot: Int): Int {
    return when (rot) {
        ROTATION_NONE -> left
        ROTATION_LANDSCAPE -> bottom
        ROTATION_UPSIDE_DOWN -> right
        else /* SEASCAPE */ -> top
    }
}

private fun Rect.logicalWidth(@Rotation rot: Int): Int {
    return when (rot) {
        ROTATION_NONE, ROTATION_UPSIDE_DOWN -> width()
        else /* LANDSCAPE, SEASCAPE */ -> height()
    }
}

private fun Int.isHorizontal(): Boolean {
    return this == ROTATION_LANDSCAPE || this == ROTATION_SEASCAPE
}

private fun Point.orientToRotZero(@Rotation rot: Int) {
    when (rot) {
        ROTATION_NONE, ROTATION_UPSIDE_DOWN -> return
        else -> {
            // swap width and height to zero-orient bounds
            val yTmp = y
            y = x
            x = yTmp
        }
    }
}

private fun Point.logicalWidth(@Rotation rot: Int): Int {
    return when (rot) {
        ROTATION_NONE, ROTATION_UPSIDE_DOWN -> x
        else -> y
    }
}
