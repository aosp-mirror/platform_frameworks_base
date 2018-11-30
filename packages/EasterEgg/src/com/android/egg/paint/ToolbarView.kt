/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.egg.paint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionListenerAdapter
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout

class ToolbarView : FrameLayout {
    var inTransition = false
    var transitionListener: Transition.TransitionListener = object : TransitionListenerAdapter() {
        override fun onTransitionStart(transition: Transition?) {
            inTransition = true
        }
        override fun onTransitionEnd(transition: Transition?) {
            inTransition = false
        }
    }

    constructor(context: Context) : super(context) {
        init(null, 0)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init(attrs, defStyle)
    }

    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        var lp = layoutParams as FrameLayout.LayoutParams?
        if (lp != null && insets != null) {
            if (insets.hasStableInsets()) {
                lp.topMargin = insets.stableInsetTop
                lp.bottomMargin = insets.stableInsetBottom
            } else {
                lp.topMargin = insets.systemWindowInsetTop
                lp.bottomMargin = insets.systemWindowInsetBottom
            }
            layoutParams = lp
        }

        return super.onApplyWindowInsets(insets)
    }

    private fun init(attrs: AttributeSet?, defStyle: Int) {
    }

}
