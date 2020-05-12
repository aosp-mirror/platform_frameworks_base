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
 * limitations under the License
 */
package com.android.systemui.media

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

private val EMPTY_RECT = Rect(0,0,0,0)

private val LAYOUT_CHANGE_LISTENER = object : View.OnLayoutChangeListener {

    override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int,
                                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
        v?.let {
            if (v.visibility == View.GONE) {
                v.clipBounds = EMPTY_RECT
            } else {
                v.clipBounds = null
            }
        }
    }
}
/**
 * A helper class that clips all GONE children. Useful for transitions in motionlayout which
 * don't clip its children.
 */
class GoneChildrenHideHelper private constructor() {
    companion object {
        @JvmStatic
        fun clipGoneChildrenOnLayout(layout: ViewGroup) {
            val childCount = layout.childCount
            for (i in 0 until childCount) {
                val child = layout.getChildAt(i)
                child.addOnLayoutChangeListener(LAYOUT_CHANGE_LISTENER)
            }
        }
    }
}