/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.plugins.clocks

import android.content.Context
import android.util.DisplayMetrics
import android.view.View
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.plugins.annotations.GeneratedImport
import com.android.systemui.plugins.annotations.ProtectedInterface
import com.android.systemui.plugins.annotations.ProtectedReturn

/** Specifies layout information for the clock face */
@ProtectedInterface
@GeneratedImport("java.util.ArrayList")
@GeneratedImport("android.view.View")
interface ClockFaceLayout {
    @get:ProtectedReturn("return new ArrayList<View>();")
    /** All clock views to add to the root constraint layout before applying constraints. */
    val views: List<View>

    @ProtectedReturn("return constraints;")
    /** Custom constraints to apply to Lockscreen ConstraintLayout. */
    fun applyConstraints(constraints: ConstraintSet): ConstraintSet

    @ProtectedReturn("return constraints;")
    /** Custom constraints to apply to preview ConstraintLayout. */
    fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        constraints: ConstraintSet,
    ): ConstraintSet

    /** Apply specified AOD BurnIn parameters to this layout */
    fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel)
}

/** Data class to contain AOD BurnIn information for correct aod rendering */
data class AodClockBurnInModel(
    /** Scale that the clock should render at to mitigate burnin */
    val scale: Float,

    /** X-Translation for the clock to mitigate burnin */
    val translationX: Float,

    /** Y-Translation for the clock to mitigate burnin */
    val translationY: Float,
)

/** A ClockFaceLayout that applies the default lockscreen layout to a single view */
class DefaultClockFaceLayout(val view: View) : ClockFaceLayout {
    override val views = listOf(view)

    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        if (views.size != 1) {
            throw IllegalArgumentException(
                "Should have only one container view when using DefaultClockFaceLayout"
            )
        }
        return constraints
    }

    override fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        constraints: ConstraintSet,
    ): ConstraintSet {
        return applyDefaultPreviewConstraints(clockPreviewConfig, constraints)
    }

    override fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel) {
        // Default clock doesn't need detailed control of view
    }

    companion object {
        fun applyDefaultPreviewConstraints(
            clockPreviewConfig: ClockPreviewConfig,
            constraints: ConstraintSet,
        ): ConstraintSet {
            constraints.apply {
                val context = clockPreviewConfig.previewContext
                val lockscreenClockViewLargeId = getId(context, "lockscreen_clock_view_large")
                constrainWidth(lockscreenClockViewLargeId, WRAP_CONTENT)
                constrainHeight(lockscreenClockViewLargeId, WRAP_CONTENT)
                constrainMaxHeight(lockscreenClockViewLargeId, 0)

                val largeClockTopMargin =
                    SystemBarUtils.getStatusBarHeight(context) +
                        getDimen(context, "small_clock_padding_top") +
                        getDimen(context, "keyguard_smartspace_top_offset") +
                        getDimen(context, "date_weather_view_height") +
                        getDimen(context, "enhanced_smartspace_height")
                connect(lockscreenClockViewLargeId, TOP, PARENT_ID, TOP, largeClockTopMargin)
                connect(lockscreenClockViewLargeId, START, PARENT_ID, START)
                connect(lockscreenClockViewLargeId, END, PARENT_ID, END)

                // In preview, we'll show UDFPS icon for UDFPS devices
                // and nothing for non-UDFPS devices,
                // and we're not planning to add this vide in clockHostView
                // so we only need position of device entry icon to constrain clock
                // Copied calculation codes from applyConstraints in DefaultDeviceEntrySection
                val bottomPaddingPx = getDimen(context, "lock_icon_margin_bottom")
                val defaultDensity =
                    DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                        DisplayMetrics.DENSITY_DEFAULT.toFloat()
                val lockIconRadiusPx = (defaultDensity * 36).toInt()
                val clockBottomMargin = bottomPaddingPx + 2 * lockIconRadiusPx

                connect(lockscreenClockViewLargeId, BOTTOM, PARENT_ID, BOTTOM, clockBottomMargin)
                val smallClockViewId = getId(context, "lockscreen_clock_view")
                constrainWidth(smallClockViewId, WRAP_CONTENT)
                constrainHeight(smallClockViewId, getDimen(context, "small_clock_height"))
                connect(
                    smallClockViewId,
                    START,
                    PARENT_ID,
                    START,
                    getDimen(context, "clock_padding_start") +
                        getDimen(context, "status_view_margin_horizontal"),
                )
                val smallClockTopMargin =
                    getSmallClockTopPadding(
                        clockPreviewConfig = clockPreviewConfig,
                        SystemBarUtils.getStatusBarHeight(context),
                    )
                connect(smallClockViewId, TOP, PARENT_ID, TOP, smallClockTopMargin)
            }
            return constraints
        }

        fun getId(context: Context, name: String): Int {
            val packageName = context.packageName
            val res = context.packageManager.getResourcesForApplication(packageName)
            val id = res.getIdentifier(name, "id", packageName)
            return id
        }

        fun getDimen(context: Context, name: String): Int {
            val packageName = context.packageName
            val res = context.resources
            val id = res.getIdentifier(name, "dimen", packageName)
            return if (id == 0) 0 else res.getDimensionPixelSize(id)
        }

        fun getSmallClockTopPadding(
            clockPreviewConfig: ClockPreviewConfig,
            statusBarHeight: Int,
        ): Int {
            return if (clockPreviewConfig.isShadeLayoutWide) {
                getDimen(clockPreviewConfig.previewContext, "keyguard_split_shade_top_margin") -
                    if (clockPreviewConfig.isSceneContainerFlagEnabled) statusBarHeight else 0
            } else {
                getDimen(clockPreviewConfig.previewContext, "keyguard_clock_top_margin") +
                    if (!clockPreviewConfig.isSceneContainerFlagEnabled) statusBarHeight else 0
            }
        }
    }
}
