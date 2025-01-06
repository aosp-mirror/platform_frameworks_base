/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.chips.ui.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A custom class for the secondary ongoing activity chip. This class will completely hide itself if
 * there isn't enough room for the mimimum size chip.
 *
 * [this.minimumWidth] must be set correctly in order for this class to work.
 */
class SecondaryOngoingActivityChip(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredWidth < this.minimumWidth) {
            // There isn't enough room to fit even the minimum content required, so hide completely.
            // Changing visibility ensures that the content description is not read aloud.
            visibility = GONE
            setMeasuredDimension(0, 0)
        }
    }
}
