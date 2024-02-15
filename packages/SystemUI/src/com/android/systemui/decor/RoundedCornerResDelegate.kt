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
import com.android.systemui.res.R
import java.io.PrintWriter

interface RoundedCornerResDelegate {
    val hasTop: Boolean
    val topRoundedDrawable: Drawable?
    val topRoundedSize: Size

    val hasBottom: Boolean
    val bottomRoundedDrawable: Drawable?
    val bottomRoundedSize: Size

    var physicalPixelDisplaySizeRatio: Float

    fun updateDisplayUniqueId(newDisplayUniqueId: String?, newReloadToken: Int?)
}

/**
 * Delegate for the device-default rounded corners. These will always be loaded from the config
 * values `R.array.config_roundedCornerTopDrawableArray` and `R.drawable.rounded_corner_top`
 */
class RoundedCornerResDelegateImpl(
    private val res: Resources,
    private var displayUniqueId: String?
) : RoundedCornerResDelegate, Dumpable {

    private var reloadToken: Int = 0

    override var hasTop: Boolean = false
        private set

    override var hasBottom: Boolean = false
        private set

    override var topRoundedDrawable: Drawable? = null
        private set

    override var bottomRoundedDrawable: Drawable? = null
        private set

    override var topRoundedSize = Size(0, 0)
        private set

    override var bottomRoundedSize = Size(0, 0)
        private set

    override var physicalPixelDisplaySizeRatio: Float = 1f
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

    override fun updateDisplayUniqueId(newDisplayUniqueId: String?, newReloadToken: Int?) {
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
