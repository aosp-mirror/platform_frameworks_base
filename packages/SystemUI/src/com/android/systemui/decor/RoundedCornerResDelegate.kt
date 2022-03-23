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

package com.android.systemui.decor

import android.annotation.ArrayRes
import android.annotation.DrawableRes
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.DisplayUtils
import android.util.Size
import android.view.RoundedCorners
import com.android.systemui.Dumpable
import com.android.systemui.R
import java.io.FileDescriptor
import java.io.PrintWriter

class RoundedCornerResDelegate(
    private val res: Resources,
    private var displayUniqueId: String?
) : Dumpable {

    private val density: Float
        get() = res.displayMetrics.density

    private var reloadToken: Int = 0

    var isMultipleRadius: Boolean = false
        private set

    private var roundedDrawable: Drawable? = null

    var topRoundedDrawable: Drawable? = null
        private set

    var bottomRoundedDrawable: Drawable? = null
        private set

    private var roundedSize = Size(0, 0)

    var topRoundedSize = Size(0, 0)
        private set

    var bottomRoundedSize = Size(0, 0)
        private set

    init {
        reloadDrawables()
        reloadMeasures()
    }

    private fun reloadAll(newReloadToken: Int) {
        if (reloadToken == newReloadToken) {
            return
        }
        reloadToken = newReloadToken
        reloadDrawables()
        reloadMeasures()
    }

    fun updateDisplayUniqueId(newDisplayUniqueId: String?, newReloadToken: Int?) {
        if (displayUniqueId != newDisplayUniqueId) {
            displayUniqueId = newDisplayUniqueId
            newReloadToken ?.let { reloadToken = it }
            reloadDrawables()
            reloadMeasures()
        } else {
            newReloadToken?.let { reloadAll(it) }
        }
    }

    private fun reloadDrawables() {
        val configIdx = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId)
        isMultipleRadius = getIsMultipleRadius(configIdx)

        roundedDrawable = getDrawable(
                displayConfigIndex = configIdx,
                arrayResId = R.array.config_roundedCornerDrawableArray,
                backupDrawableId = R.drawable.rounded
        )
        topRoundedDrawable = getDrawable(
                displayConfigIndex = configIdx,
                arrayResId = R.array.config_roundedCornerTopDrawableArray,
                backupDrawableId = R.drawable.rounded_corner_top
        ) ?: roundedDrawable
        bottomRoundedDrawable = getDrawable(
                displayConfigIndex = configIdx,
                arrayResId = R.array.config_roundedCornerBottomDrawableArray,
                backupDrawableId = R.drawable.rounded_corner_bottom
        ) ?: roundedDrawable
    }

    private fun reloadMeasures(roundedSizeFactor: Int? = null) {
        // If config_roundedCornerMultipleRadius set as true, ScreenDecorations respect the
        // (width, height) size of drawable/rounded.xml instead of rounded_corner_radius
        if (isMultipleRadius) {
            roundedSize = Size(
                    roundedDrawable?.intrinsicWidth ?: 0,
                    roundedDrawable?.intrinsicHeight ?: 0)
            topRoundedDrawable?.let {
                topRoundedSize = Size(it.intrinsicWidth, it.intrinsicHeight)
            }
            bottomRoundedDrawable?.let {
                bottomRoundedSize = Size(it.intrinsicWidth, it.intrinsicHeight)
            }
        } else {
            val defaultRadius = RoundedCorners.getRoundedCornerRadius(res, displayUniqueId)
            val topRadius = RoundedCorners.getRoundedCornerTopRadius(res, displayUniqueId)
            val bottomRadius = RoundedCorners.getRoundedCornerBottomRadius(res, displayUniqueId)
            roundedSize = Size(defaultRadius, defaultRadius)
            topRoundedSize = Size(topRadius, topRadius)
            bottomRoundedSize = Size(bottomRadius, bottomRadius)
        }

        if (topRoundedSize.width == 0) {
            topRoundedSize = roundedSize
        }
        if (bottomRoundedSize.width == 0) {
            bottomRoundedSize = roundedSize
        }

        if (roundedSizeFactor != null && roundedSizeFactor > 0) {
            val length: Int = (roundedSizeFactor * density).toInt()
            topRoundedSize = Size(length, length)
            bottomRoundedSize = Size(length, length)
        }
    }

    fun updateTuningSizeFactor(factor: Int?, newReloadToken: Int) {
        if (reloadToken == newReloadToken) {
            return
        }
        reloadToken = newReloadToken
        reloadMeasures(factor)
    }

    /**
     * Gets whether the rounded corners are multiple radii for current display.
     *
     * Loads the default config {@link R.bool#config_roundedCornerMultipleRadius} if
     * {@link com.android.internal.R.array#config_displayUniqueIdArray} is not set.
     */
    private fun getIsMultipleRadius(displayConfigIndex: Int): Boolean {
        val isMultipleRadius: Boolean
        res.obtainTypedArray(R.array.config_roundedCornerMultipleRadiusArray).let { array ->
            isMultipleRadius = if (displayConfigIndex >= 0 && displayConfigIndex < array.length()) {
                array.getBoolean(displayConfigIndex, false)
            } else {
                res.getBoolean(R.bool.config_roundedCornerMultipleRadius)
            }
            array.recycle()
        }
        return isMultipleRadius
    }

    private fun getDrawable(
        displayConfigIndex: Int,
        @ArrayRes arrayResId: Int,
        @DrawableRes backupDrawableId: Int
    ): Drawable? {
        val drawable: Drawable?
        res.obtainTypedArray(arrayResId).let { array ->
            drawable = if (displayConfigIndex >= 0 && displayConfigIndex < array.length()) {
                array.getDrawable(displayConfigIndex)
            } else {
                res.getDrawable(backupDrawableId, null)
            }
            array.recycle()
        }
        return drawable
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("RoundedCornerResDelegate state:")
        pw.println("  isMultipleRadius:$isMultipleRadius")
        pw.println("  roundedSize(w,h)=(${roundedSize.width},${roundedSize.height})")
        pw.println("  topRoundedSize(w,h)=(${topRoundedSize.width},${topRoundedSize.height})")
        pw.println("  bottomRoundedSize(w,h)=(${bottomRoundedSize.width}," +
                "${bottomRoundedSize.height})")
    }
}