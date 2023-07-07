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
import java.io.PrintWriter

class RoundedCornerResDelegate(
    private val res: Resources,
    private var displayUniqueId: String?
) : Dumpable {

    private val density: Float
        get() = res.displayMetrics.density

    private var reloadToken: Int = 0

    var hasTop: Boolean = false
        private set

    var hasBottom: Boolean = false
        private set

    var topRoundedDrawable: Drawable? = null
        private set

    var bottomRoundedDrawable: Drawable? = null
        private set

    var topRoundedSize = Size(0, 0)
        private set

    var bottomRoundedSize = Size(0, 0)
        private set

    var tuningSizeFactor: Int? = null
        set(value) {
            if (field == value) {
                return
            }
            field = value
            reloadMeasures()
        }

    var physicalPixelDisplaySizeRatio: Float = 1f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            reloadMeasures()
        }

    init {
        reloadRes()
        reloadMeasures()
    }

    fun updateDisplayUniqueId(newDisplayUniqueId: String?, newReloadToken: Int?) {
        if (displayUniqueId != newDisplayUniqueId) {
            displayUniqueId = newDisplayUniqueId
            newReloadToken ?.let { reloadToken = it }
            reloadRes()
            reloadMeasures()
        } else if (newReloadToken != null) {
            if (reloadToken == newReloadToken) {
                return
            }
            reloadToken = newReloadToken
            reloadMeasures()
        }
    }

    private fun reloadRes() {
        val configIdx = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId)

        val hasDefaultRadius = RoundedCorners.getRoundedCornerRadius(res, displayUniqueId) > 0
        hasTop = hasDefaultRadius ||
            (RoundedCorners.getRoundedCornerTopRadius(res, displayUniqueId) > 0)
        hasBottom = hasDefaultRadius ||
            (RoundedCorners.getRoundedCornerBottomRadius(res, displayUniqueId) > 0)

        topRoundedDrawable = getDrawable(
            displayConfigIndex = configIdx,
            arrayResId = R.array.config_roundedCornerTopDrawableArray,
            backupDrawableId = R.drawable.rounded_corner_top
        )
        bottomRoundedDrawable = getDrawable(
            displayConfigIndex = configIdx,
            arrayResId = R.array.config_roundedCornerBottomDrawableArray,
            backupDrawableId = R.drawable.rounded_corner_bottom
        )
    }

    private fun reloadMeasures() {
        topRoundedDrawable?.let {
            topRoundedSize = Size(it.intrinsicWidth, it.intrinsicHeight)
        }
        bottomRoundedDrawable?.let {
            bottomRoundedSize = Size(it.intrinsicWidth, it.intrinsicHeight)
        }

        tuningSizeFactor?.let {
            if (it <= 0) {
                return
            }
            val length: Int = (it * density).toInt()
            if (topRoundedSize.width > 0) {
                topRoundedSize = Size(length, length)
            }
            if (bottomRoundedSize.width > 0) {
                bottomRoundedSize = Size(length, length)
            }
        }

        if (physicalPixelDisplaySizeRatio != 1f) {
            if (topRoundedSize.width != 0) {
                topRoundedSize = Size(
                    (physicalPixelDisplaySizeRatio * topRoundedSize.width + 0.5f).toInt(),
                    (physicalPixelDisplaySizeRatio * topRoundedSize.height + 0.5f).toInt()
                )
            }
            if (bottomRoundedSize.width != 0) {
                bottomRoundedSize = Size(
                    (physicalPixelDisplaySizeRatio * bottomRoundedSize.width + 0.5f).toInt(),
                    (physicalPixelDisplaySizeRatio * bottomRoundedSize.height + 0.5f).toInt()
                )
            }
        }
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

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("RoundedCornerResDelegate state:")
        pw.println("  hasTop=$hasTop")
        pw.println("  hasBottom=$hasBottom")
        pw.println("  topRoundedSize(w,h)=(${topRoundedSize.width},${topRoundedSize.height})")
        pw.println(
            "  bottomRoundedSize(w,h)=(${bottomRoundedSize.width},${bottomRoundedSize.height})"
        )
        pw.println("  physicalPixelDisplaySizeRatio=$physicalPixelDisplaySizeRatio")
    }
}
