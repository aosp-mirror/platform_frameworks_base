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
import android.util.AttributeSet
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout

class CutoutAvoidingToolbar : LinearLayout {
    private var _insets: WindowInsets? = null

    constructor(context: Context) : super(context) {
        init(null, 0)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init(attrs, defStyle)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        adjustLayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        _insets = insets
        adjustLayout()
        return super.onApplyWindowInsets(insets)
    }

    fun adjustLayout() {
        _insets?.displayCutout?.boundingRects?.let {
            var cutoutCenter = 0
            var cutoutLeft = 0
            var cutoutRight = 0

            // collect at most three cutouts
            for (r in it) {
                if (r.top > 0) continue

                if (r.left == left) {
                    cutoutLeft = r.width()
                } else if (r.right == right) {
                    cutoutRight = r.width()
                } else {
                    cutoutCenter = r.width()
                }
            }

            // apply to layout
            (findViewWithTag("cutoutLeft") as View?)?.let {
                it.layoutParams = LayoutParams(cutoutLeft, MATCH_PARENT)
            }
            (findViewWithTag("cutoutCenter") as View?)?.let {
                it.layoutParams = LayoutParams(cutoutCenter, MATCH_PARENT)
            }
            (findViewWithTag("cutoutRight") as View?)?.let {
                it.layoutParams = LayoutParams(cutoutRight, MATCH_PARENT)
            }

            requestLayout()
        }
    }

    private fun init(attrs: AttributeSet?, defStyle: Int) {
    }

}
