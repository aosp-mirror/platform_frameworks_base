/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.app.ActivityManager
import android.content.res.Resources
import android.os.SystemProperties
import android.os.Trace
import android.os.Trace.TRACE_TAG_APP
import android.util.IndentingPrintWriter
import android.util.MathUtils
import android.view.CrossWindowBlurListeners
import android.view.CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED
import android.view.SurfaceControl
import android.view.ViewRootImpl
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
open class BlurUtils @Inject constructor(
    @Main private val resources: Resources,
    private val crossWindowBlurListeners: CrossWindowBlurListeners,
    dumpManager: DumpManager
) : Dumpable {
    val minBlurRadius = resources.getDimensionPixelSize(R.dimen.min_window_blur_radius)
    val maxBlurRadius = resources.getDimensionPixelSize(R.dimen.max_window_blur_radius)
    private var lastAppliedBlur = 0
    private var earlyWakeupEnabled = false

    init {
        dumpManager.registerDumpable(this)
    }

    /**
     * Translates a ratio from 0 to 1 to a blur radius in pixels.
     */
    fun blurRadiusOfRatio(ratio: Float): Float {
        if (ratio == 0f) {
            return 0f
        }
        return MathUtils.lerp(minBlurRadius.toFloat(), maxBlurRadius.toFloat(), ratio)
    }

    /**
     * Translates a blur radius in pixels to a ratio between 0 to 1.
     */
    fun ratioOfBlurRadius(blur: Float): Float {
        if (blur == 0f) {
            return 0f
        }
        return MathUtils.map(minBlurRadius.toFloat(), maxBlurRadius.toFloat(),
                0f /* maxStart */, 1f /* maxStop */, blur)
    }

    /**
     * This method should be called before [applyBlur] so that, if needed, we can set the
     * early-wakeup flag in SurfaceFlinger.
     */
    fun prepareBlur(viewRootImpl: ViewRootImpl?, radius: Int) {
        if (viewRootImpl == null || !viewRootImpl.surfaceControl.isValid ||
            !supportsBlursOnWindows() || earlyWakeupEnabled
        ) {
            return
        }
        if (lastAppliedBlur == 0 && radius != 0) {
            Trace.asyncTraceForTrackBegin(
                    TRACE_TAG_APP, TRACK_NAME, "eEarlyWakeup (prepareBlur)", 0)
            earlyWakeupEnabled = true
            createTransaction().use {
                it.setEarlyWakeupStart()
                it.apply()
            }
        }
    }

    /**
     * Applies background blurs to a {@link ViewRootImpl}.
     *
     * @param viewRootImpl The window root.
     * @param radius blur radius in pixels.
     * @param opaque if surface is opaque, regardless or having blurs or no.
     */
    fun applyBlur(viewRootImpl: ViewRootImpl?, radius: Int, opaque: Boolean) {
        if (viewRootImpl == null || !viewRootImpl.surfaceControl.isValid) {
            return
        }
        createTransaction().use {
            if (supportsBlursOnWindows()) {
                it.setBackgroundBlurRadius(viewRootImpl.surfaceControl, radius)
                if (!earlyWakeupEnabled && lastAppliedBlur == 0 && radius != 0) {
                    Trace.asyncTraceForTrackBegin(
                        TRACE_TAG_APP,
                        TRACK_NAME,
                        "eEarlyWakeup (applyBlur)",
                        0
                    )
                    it.setEarlyWakeupStart()
                    earlyWakeupEnabled = true
                }
                if (earlyWakeupEnabled && lastAppliedBlur != 0 && radius == 0) {
                    it.setEarlyWakeupEnd()
                    Trace.asyncTraceForTrackEnd(TRACE_TAG_APP, TRACK_NAME, 0)
                    earlyWakeupEnabled = false
                }
                lastAppliedBlur = radius
            }
            it.setOpaque(viewRootImpl.surfaceControl, opaque)
            it.apply()
        }
    }

    @VisibleForTesting
    open fun createTransaction(): SurfaceControl.Transaction {
        return SurfaceControl.Transaction()
    }

    /**
     * If this device can render blurs.
     *
     * @see android.view.SurfaceControl.Transaction#setBackgroundBlurRadius(SurfaceControl, int)
     * @return {@code true} when supported.
     */
    open fun supportsBlursOnWindows(): Boolean {
        return CROSS_WINDOW_BLUR_SUPPORTED && ActivityManager.isHighEndGfx() &&
                crossWindowBlurListeners.isCrossWindowBlurEnabled() &&
                !SystemProperties.getBoolean("persist.sysui.disableBlur", false)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("BlurUtils:")
            it.increaseIndent()
            it.println("minBlurRadius: $minBlurRadius")
            it.println("maxBlurRadius: $maxBlurRadius")
            it.println("supportsBlursOnWindows: ${supportsBlursOnWindows()}")
            it.println("CROSS_WINDOW_BLUR_SUPPORTED: $CROSS_WINDOW_BLUR_SUPPORTED")
            it.println("isHighEndGfx: ${ActivityManager.isHighEndGfx()}")
        }
    }

    companion object {
        const val TRACK_NAME = "BlurUtils"
    }
}
