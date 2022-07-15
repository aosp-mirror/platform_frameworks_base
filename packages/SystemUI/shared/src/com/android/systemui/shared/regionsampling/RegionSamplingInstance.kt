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

import android.content.res.Resources
import android.graphics.Rect
import android.view.View
import com.android.systemui.plugins.Clock
import com.android.systemui.plugins.RegionDarkness
import com.android.systemui.shared.navigationbar.RegionSamplingHelper
import com.android.systemui.shared.navigationbar.RegionSamplingHelper.SamplingCallback
import java.io.PrintWriter
import java.util.concurrent.Executor

/**
 * Class for instance of RegionSamplingHelper
 */
class RegionSamplingInstance(
        sampledView: View?,
        mainExecutor: Executor?,
        bgExecutor: Executor?,
        regionSamplingEnabled: Boolean,
        clock: Clock?,
        resources: Resources
) {
    private var isDark = RegionDarkness.DEFAULT
    private var samplingBounds = Rect()
    private var regionSampler: RegionSamplingHelper? = null

    private fun convertToClockDarkness(isRegionDark: Boolean): RegionDarkness {
        return if (isRegionDark) {
            RegionDarkness.DARK
        } else {
            RegionDarkness.LIGHT
        }
    }

    fun createRegionSamplingInstance(): Pair<RegionDarkness, Rect> {
        return Pair(isDark, samplingBounds)
    }

    fun currentClockDarkness(): RegionDarkness {
        return isDark
    }

    /**
     * Start region sampler
     */
    fun startRegionSampler() {
        regionSampler?.start(samplingBounds)
    }

    /**
     * Stop region sampler
     */
    fun stopRegionSampler() {
        regionSampler?.stop()
    }

    /**
     * Dump region sampler
     */
    fun dump(pw: PrintWriter) {
        regionSampler?.dump(pw)
    }

    /**
     * Restart
     */
    fun restart(sampledView: View?) {
        regionSampler?.onViewAttachedToWindow(sampledView)
    }

    init {
        if (regionSamplingEnabled && sampledView != null) {
            regionSampler = RegionSamplingHelper(sampledView,
                    object : SamplingCallback {
                        override fun onRegionDarknessChanged(isRegionDark: Boolean) {
                            isDark = convertToClockDarkness(isRegionDark)
                            clock?.events?.onColorPaletteChanged(resources, isDark, isDark)
                        }

                        override fun getSampledRegion(sampledView: View): Rect {
                            samplingBounds = Rect(sampledView.left, sampledView.top,
                                    sampledView.right, sampledView.bottom)
                            return samplingBounds
                        }

                        override fun isSamplingEnabled(): Boolean {
                            return regionSamplingEnabled
                        }
                    }, mainExecutor, bgExecutor)
        }
        regionSampler?.setWindowVisible(true)
    }
}
