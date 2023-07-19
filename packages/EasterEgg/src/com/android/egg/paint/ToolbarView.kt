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
import android.transition.Transition
import android.transition.TransitionListenerAdapter
import android.util.AttributeSet
import android.view.*
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
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
    }

    constructor(
        context: Context,
        attrs: AttributeSet,
        defStyle: Int
    ) : super(context, attrs, defStyle) {
    }

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        var lp = layoutParams as FrameLayout.LayoutParams?
        if (lp != null && insets != null) {
            var stableInsets = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            if (stableInsets.top != 0 || stableInsets.bottom != 0 || stableInsets.left != 0 ||
                    stableInsets.right != 0) {
                lp.topMargin = stableInsets.top
                lp.bottomMargin = stableInsets.bottom
            } else {
                var systemInsets = insets.getInsets(WindowInsets.Type.systemBars());
                lp.topMargin = systemInsets.top
                lp.bottomMargin = systemInsets.bottom
            }
            layoutParams = lp
        }

        return super.onApplyWindowInsets(insets)
    }
}
