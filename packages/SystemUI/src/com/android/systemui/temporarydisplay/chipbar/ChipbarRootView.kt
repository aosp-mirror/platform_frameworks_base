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

package com.android.systemui.temporarydisplay.chipbar

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import com.android.systemui.Gefingerpoken

/** A simple subclass that allows for observing touch events on chipbar. */
class ChipbarRootView(
        context: Context,
        attrs: AttributeSet?
) : FrameLayout(context, attrs) {

    /** Assign this field to observe touch events. */
    var touchHandler: Gefingerpoken? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchHandler?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
