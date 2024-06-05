/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.annotation.IntRange
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.statusbar.events.BackgroundAnimatableView

class BatteryStatusChip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), BackgroundAnimatableView {

    private val roundedContainer: LinearLayout
    private val batteryMeterView: BatteryMeterView
    override val contentView: View
        get() = batteryMeterView

    init {
        inflate(context, R.layout.battery_status_chip, this)
        roundedContainer = requireViewById(R.id.rounded_container)
        batteryMeterView = requireViewById(R.id.battery_meter_view)
        updateResources()
    }

    /**
     * When animating as a chip in the status bar, we want to animate the width for the rounded
     * container. We have to subtract our own top and left offset because the bounds come to us as
     * absolute on-screen bounds.
     */
    override fun setBoundsForAnimation(l: Int, t: Int, r: Int, b: Int) {
        roundedContainer.setLeftTopRightBottom(l - left, t - top, r - left, b - top)
    }

    fun setBatteryLevel(@IntRange(from = 0, to = 100) batteryLevel: Int) {
        batteryMeterView.setForceShowPercent(true)
        batteryMeterView.onBatteryLevelChanged(batteryLevel, true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun updateResources() {
        val primaryColor =
            Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.colorPrimary)
        val textColorSecondary =
            Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorSecondary)
        batteryMeterView.updateColors(primaryColor, textColorSecondary, primaryColor)
        roundedContainer.background = mContext.getDrawable(R.drawable.statusbar_chip_bg)
    }
}
