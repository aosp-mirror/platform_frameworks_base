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

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.widget.FrameLayout
import com.android.systemui.res.R
import com.android.systemui.statusbar.events.BackgroundAnimatableView

/** Chip that appears in the status bar when an external display is connected. */
class ConnectedDisplayChip
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), BackgroundAnimatableView {

    private val iconContainer: FrameLayout
    init {
        inflate(context, R.layout.connected_display_chip, this)
        iconContainer = requireViewById(R.id.icons_rounded_container)
    }

    /**
     * When animating as a chip in the status bar, we want to animate the width for the rounded
     * container. We have to subtract our own top and left offset because the bounds come to us as
     * absolute on-screen bounds.
     */
    override fun setBoundsForAnimation(l: Int, t: Int, r: Int, b: Int) {
        iconContainer.setLeftTopRightBottom(l - left, t - top, r - left, b - top)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun updateResources() {
        iconContainer.background = context.getDrawable(R.drawable.statusbar_chip_bg)
    }
}
