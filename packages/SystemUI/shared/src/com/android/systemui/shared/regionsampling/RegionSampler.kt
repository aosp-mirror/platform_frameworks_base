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
package com.android.systemui.shared.regionsampling

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.shared.navigationbar.RegionSamplingHelper
import java.io.PrintWriter
import java.util.concurrent.Executor

/** Class for instance of RegionSamplingHelper */
open class RegionSampler
@JvmOverloads
constructor(
    val sampledView: View?,
    mainExecutor: Executor?,
    val bgExecutor: Executor?,
    val regionSamplingEnabled: Boolean,
    val updateForegroundColor: UpdateColorCallback,
    val wallpaperManager: WallpaperManager? = WallpaperManager.getInstance(sampledView?.context)
) : WallpaperManager.LocalWallpaperColorConsumer {
    private var regionDarkness = RegionDarkness.DEFAULT
    private var samplingBounds = Rect()
    private val tmpScreenLocation = IntArray(2)
    @VisibleForTesting var regionSampler: RegionSamplingHelper? = null
    private var lightForegroundColor = Color.WHITE
    private var darkForegroundColor = Color.BLACK
    private val displaySize = Point()

    /**
     * Sets the colors to be used for Dark and Light Foreground.
     *
     * @param lightColor The color used for Light Foreground.
     * @param darkColor The color used for Dark Foreground.
     */
    fun setForegroundColors(lightColor: Int, darkColor: Int) {
        lightForegroundColor = lightColor
        darkForegroundColor = darkColor
    }

    /**
     * Determines which foreground color to use based on region darkness.
     *
     * @return the determined foreground color
     */
    fun currentForegroundColor(): Int {
        return if (regionDarkness.isDark) {
            lightForegroundColor
        } else {
            darkForegroundColor
        }
    }

    private fun getRegionDarkness(isRegionDark: Boolean): RegionDarkness {
        return if (isRegionDark) {
            RegionDarkness.DARK
        } else {
            RegionDarkness.LIGHT
        }
    }

    fun currentRegionDarkness(): RegionDarkness {
        return regionDarkness
    }

    /** Start region sampler */
    fun startRegionSampler() {
        if (!regionSamplingEnabled || sampledView == null) {
            return
        }

        val sampledRegion = calculateSampledRegion(sampledView)
        val regions = ArrayList<RectF>()
        val sampledRegionWithOffset = convertBounds(sampledRegion)

        if (
            sampledRegionWithOffset.left < 0.0 ||
                sampledRegionWithOffset.right > 1.0 ||
                sampledRegionWithOffset.top < 0.0 ||
                sampledRegionWithOffset.bottom > 1.0
        ) {
            android.util.Log.e(
                "RegionSampler",
                "view out of bounds: $sampledRegion | " +
                    "screen width: ${displaySize.x}, screen height: ${displaySize.y}",
                Exception()
            )
            return
        }

        regions.add(sampledRegionWithOffset)

        wallpaperManager?.removeOnColorsChangedListener(this)
        wallpaperManager?.addOnColorsChangedListener(this, regions)

        // TODO(b/265969235): conditionally set FLAG_LOCK or FLAG_SYSTEM once HS smartspace
        // implemented
        bgExecutor?.execute(
            Runnable {
                val initialSampling =
                    wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_LOCK)
                onColorsChanged(sampledRegionWithOffset, initialSampling)
            }
        )
    }

    /** Stop region sampler */
    fun stopRegionSampler() {
        wallpaperManager?.removeOnColorsChangedListener(this)
    }

    /** Dump region sampler */
    fun dump(pw: PrintWriter) {
        pw.println("[RegionSampler]")
        pw.println("regionSamplingEnabled: $regionSamplingEnabled")
        pw.println("regionDarkness: $regionDarkness")
        pw.println("lightForegroundColor: ${Integer.toHexString(lightForegroundColor)}")
        pw.println("darkForegroundColor: ${Integer.toHexString(darkForegroundColor)}")
        pw.println("passed-in sampledView: $sampledView")
        pw.println("calculated samplingBounds: $samplingBounds")
        pw.println(
            "sampledView width: ${sampledView?.width}, sampledView height: ${sampledView?.height}"
        )
        pw.println("screen width: ${displaySize.x}, screen height: ${displaySize.y}")
        pw.println(
            "sampledRegionWithOffset: ${convertBounds(calculateSampledRegion(sampledView!!))}"
        )
        // TODO(b/265969235): mock initialSampling based on if component is on HS or LS wallpaper
        // HS Smartspace - wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        // LS Smartspace, clock - wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_LOCK)
        pw.println(
            "initialSampling for lockscreen: " +
                "${wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_LOCK)}"
        )
    }

    fun calculateSampledRegion(sampledView: View): RectF {
        val screenLocation = tmpScreenLocation
        /**
         * The method getLocationOnScreen is used to obtain the view coordinates relative to its
         * left and top edges on the device screen. Directly accessing the X and Y coordinates of
         * the view returns the location relative to its parent view instead.
         */
        sampledView.getLocationOnScreen(screenLocation)
        val left = screenLocation[0]
        val top = screenLocation[1]

        samplingBounds.left = left
        samplingBounds.top = top
        samplingBounds.right = left + sampledView.width
        samplingBounds.bottom = top + sampledView.height

        return RectF(samplingBounds)
    }

    /**
     * Convert the bounds of the region we want to sample from to fractional offsets because
     * WallpaperManager requires the bounds to be between [0,1]. The wallpaper is treated as one
     * continuous image, so if there are multiple screens, then each screen falls into a fractional
     * range. For instance, 4 screens have the ranges [0, 0.25], [0,25, 0.5], [0.5, 0.75], [0.75,
     * 1].
     */
    fun convertBounds(originalBounds: RectF): RectF {

        // TODO(b/265969235): GRAB # PAGES + CURRENT WALLPAPER PAGE # FROM LAUNCHER
        // TODO(b/265968912): remove hard-coded value once LS wallpaper supported
        val wallpaperPageNum = 0
        val numScreens = 1

        val screenWidth = displaySize.x
        // TODO: investigate small difference between this and the height reported in go/web-hv
        val screenHeight = displaySize.y

        val newBounds = RectF()
        // horizontal
        newBounds.left = ((originalBounds.left / screenWidth) + wallpaperPageNum) / numScreens
        newBounds.right = ((originalBounds.right / screenWidth) + wallpaperPageNum) / numScreens
        // vertical
        newBounds.top = originalBounds.top / screenHeight
        newBounds.bottom = originalBounds.bottom / screenHeight

        return newBounds
    }

    init {
        sampledView?.context?.display?.getSize(displaySize)
    }

    override fun onColorsChanged(area: RectF?, colors: WallpaperColors?) {
        // update text color when wallpaper color changes
        regionDarkness =
            getRegionDarkness(
                (colors?.colorHints?.and(WallpaperColors.HINT_SUPPORTS_DARK_TEXT)) !=
                    WallpaperColors.HINT_SUPPORTS_DARK_TEXT
            )
        updateForegroundColor()
    }
}

typealias UpdateColorCallback = () -> Unit
