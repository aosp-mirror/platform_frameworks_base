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

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.shared.navigationbar.RegionSamplingHelper
import com.android.systemui.shared.navigationbar.RegionSamplingHelper.SamplingCallback
import java.io.PrintWriter
import java.util.concurrent.Executor

/** Class for instance of RegionSamplingHelper */
open class RegionSampler(
    sampledView: View?,
    mainExecutor: Executor?,
    bgExecutor: Executor?,
    regionSamplingEnabled: Boolean,
    updateFun: UpdateColorCallback
) {
    private var regionDarkness = RegionDarkness.DEFAULT
    private var samplingBounds = Rect()
    private val tmpScreenLocation = IntArray(2)
    @VisibleForTesting var regionSampler: RegionSamplingHelper? = null
    private var lightForegroundColor = Color.WHITE
    private var darkForegroundColor = Color.BLACK

    @VisibleForTesting
    open fun createRegionSamplingHelper(
        sampledView: View,
        callback: SamplingCallback,
        mainExecutor: Executor?,
        bgExecutor: Executor?
    ): RegionSamplingHelper {
        return RegionSamplingHelper(sampledView, callback, mainExecutor, bgExecutor)
    }

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

    private fun convertToClockDarkness(isRegionDark: Boolean): RegionDarkness {
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
        regionSampler?.start(samplingBounds)
    }

    /** Stop region sampler */
    fun stopRegionSampler() {
        regionSampler?.stop()
    }

    /** Dump region sampler */
    fun dump(pw: PrintWriter) {
        regionSampler?.dump(pw)
    }

    init {
        if (regionSamplingEnabled && sampledView != null) {
            regionSampler =
                createRegionSamplingHelper(
                    sampledView,
                    object : SamplingCallback {
                        override fun onRegionDarknessChanged(isRegionDark: Boolean) {
                            regionDarkness = convertToClockDarkness(isRegionDark)
                            updateFun()
                        }
                        /**
                         * The method getLocationOnScreen is used to obtain the view coordinates
                         * relative to its left and top edges on the device screen. Directly
                         * accessing the X and Y coordinates of the view returns the location
                         * relative to its parent view instead.
                         */
                        override fun getSampledRegion(sampledView: View): Rect {
                            val screenLocation = tmpScreenLocation
                            sampledView.getLocationOnScreen(screenLocation)
                            val left = screenLocation[0]
                            val top = screenLocation[1]
                            samplingBounds.left = left
                            samplingBounds.top = top
                            samplingBounds.right = left + sampledView.width
                            samplingBounds.bottom = top + sampledView.height
                            return samplingBounds
                        }

                        override fun isSamplingEnabled(): Boolean {
                            return regionSamplingEnabled
                        }
                    },
                    mainExecutor,
                    bgExecutor
                )
        }
        regionSampler?.setWindowVisible(true)
    }
}

typealias UpdateColorCallback = () -> Unit
