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
import android.graphics.Rect
import android.util.LruCache
import android.util.Pair
import android.view.DisplayCutout
import android.view.View.LAYOUT_DIRECTION_RTL
import android.view.WindowMetrics
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.leak.RotationUtils
import com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_NONE
import com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE
import com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN
import com.android.systemui.util.leak.RotationUtils.Rotation
import com.android.systemui.util.leak.RotationUtils.getResourcesForRotation
import java.io.FileDescriptor
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

    override fun onOverlayChanged() {
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
     * Calculates the maximum bounding rectangle for the privacy chip animation + ongoing privacy
     * dot in the coordinates relative to the given rotation.
     */
    fun getBoundingRectForPrivacyChipForRotation(@Rotation rotation: Int): Rect {
        var insets = insetsCache[getCacheKey(rotation = rotation)]
        val rotatedResources = getResourcesForRotation(rotation, context)
        if (insets == null) {
            insets = getStatusBarContentInsetsForRotation(rotation, rotatedResources)
        }

        val dotWidth = rotatedResources.getDimensionPixelSize(R.dimen.ongoing_appops_dot_diameter)
        val chipWidth = rotatedResources.getDimensionPixelSize(
                R.dimen.ongoing_appops_chip_max_width)

        val isRtl = context.resources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL
        return getPrivacyChipBoundingRectForInsets(insets, dotWidth, chipWidth, isRtl)
    }

    /**
     * Calculates the necessary left and right locations for the status bar contents invariant of
     * the current device rotation, in the target rotation's coordinates
     */
    @JvmOverloads
    fun getStatusBarContentInsetsForRotation(
        @Rotation rotation: Int,
        rotatedResources: Resources = getResourcesForRotation(rotation, context)
    ): Rect {
        val key = getCacheKey(rotation = rotation)
        return insetsCache[key] ?: getCalculatedInsetsForRotation(rotation, rotatedResources)
            .also {
                insetsCache.put(key, it)
            }
    }

    private fun getCalculatedInsetsForRotation(
        @Rotation targetRotation: Int,
        rotatedResources: Resources
    ): Rect {
        val dc = context.display.cutout
        val currentRotation = RotationUtils.getExactRotation(context)

        val isRtl = rotatedResources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL
        val roundedCornerPadding = rotatedResources
                .getDimensionPixelSize(R.dimen.rounded_corner_content_padding)
        val minDotWidth = if (isPrivacyDotEnabled)
                rotatedResources.getDimensionPixelSize(R.dimen.ongoing_appops_dot_min_padding)
            else 0

        val minLeft: Int
        val minRight: Int
        if (isRtl) {
            minLeft = max(minDotWidth, roundedCornerPadding)
            minRight = roundedCornerPadding
        } else {
            minLeft = roundedCornerPadding
            minRight = max(minDotWidth, roundedCornerPadding)
        }

        return calculateInsetsForRotationWithRotatedResources(
                currentRotation,
                targetRotation,
                dc,
                context.resources.configuration.windowConfiguration.maxBounds,
                rotatedResources.getDimensionPixelSize(R.dimen.status_bar_height),
                minLeft,
                minRight)
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        insetsCache.snapshot().forEach { (key, rect) ->
            pw.println("$key -> $rect")
        }
        pw.println(insetsCache)
    }

    private fun getCacheKey(@Rotation rotation: Int): CacheKey =
        CacheKey(
            uniqueDisplayId = context.display.uniqueId,
            rotation = rotation
        )

    private data class CacheKey(
        val uniqueDisplayId: String,
        @Rotation val rotation: Int
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
 * @param displayCutout [DisplayCutout] for the curren display. possibly null
 * @param windowMetrics [WindowMetrics] for the current window
 * @param statusBarHeight height of the status bar for the target rotation
 * @param roundedCornerPadding from rounded_corner_content_padding
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
    minRight: Int
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
            targetRotation,
            currentRotation)

    return sbLeftRight
}

/**
 * Calculate the insets needed from the left and right edges for the given rotation.
 *
 * @param dc Device display cutout
 * @param sbHeight appropriate status bar height for this rotation
 * @param width display width calculated for ROTATION_NONE
 * @param height display height calculated for ROTATION_NONE
 * @param cWidth display width in our current rotation
 * @param cHeight display height in our current rotation
 * @param minLeft the minimum padding to enforce on the left
 * @param minRight the minimum padding to enforce on the right
 * @param targetRotation the rotation for which to calculate margins
 * @param currentRotation the rotation from which the display cutout was generated
 *
 * @return a Rect which exactly calculates the Status Bar's content rect relative to the target
 * rotation
 */
private fun getStatusBarLeftRight(
    dc: DisplayCutout?,
    sbHeight: Int,
    width: Int,
    height: Int,
    cWidth: Int,
    cHeight: Int,
    minLeft: Int,
    minRight: Int,
    @Rotation targetRotation: Int,
    @Rotation currentRotation: Int
): Rect {

    val logicalDisplayWidth = if (targetRotation.isHorizontal()) height else width

    val cutoutRects = dc?.boundingRects
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

            val l = max(minLeft, cutoutRect.logicalWidth(relativeRotation))
            leftMargin = max(l, leftMargin)
        } else if (cutoutRect.touchesRightEdge(relativeRotation, cWidth, cHeight)) {
            val logicalWidth = cutoutRect.logicalWidth(relativeRotation)
            rightMargin = max(minRight, logicalWidth)
        }
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
