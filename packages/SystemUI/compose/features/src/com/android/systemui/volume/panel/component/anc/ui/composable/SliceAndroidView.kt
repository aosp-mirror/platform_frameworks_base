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

package com.android.systemui.volume.panel.component.anc.ui.composable

import android.annotation.SuppressLint
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.slice.Slice
import androidx.slice.widget.SliceView
import com.android.systemui.res.R

@Composable
fun SliceAndroidView(
    slice: Slice?,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    onWidthChanged: ((Int) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { context: Context ->
            ClickableSliceView(
                    ContextThemeWrapper(context, R.style.Widget_SliceView_VolumePanel),
                )
                .apply {
                    mode = SliceView.MODE_LARGE
                    isScrollable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setShowTitleItems(true)
                    if (onWidthChanged != null) {
                        addOnLayoutChangeListener(OnWidthChangedLayoutListener(onWidthChanged))
                    }
                }
        },
        update = { sliceView: ClickableSliceView ->
            sliceView.slice = slice
            sliceView.onClick = onClick
            sliceView.isEnabled = isEnabled
            sliceView.isClickable = isEnabled
        }
    )
}

class OnWidthChangedLayoutListener(private val widthChanged: (Int) -> Unit) :
    View.OnLayoutChangeListener {

    override fun onLayoutChange(
        v: View?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int
    ) {
        val newWidth = right - left
        val oldWidth = oldRight - oldLeft
        if (oldWidth != newWidth) {
            widthChanged(newWidth)
        }
    }
}

/**
 * [SliceView] that prioritises [onClick] when its clicked instead of passing the event to the slice
 * first.
 */
@SuppressLint("ViewConstructor") // only used in this class
private class ClickableSliceView(context: Context) : SliceView(context) {

    var onClick: (() -> Unit)? = null

    init {
        if (onClick != null) {
            setOnClickListener {}
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return (isSliceViewClickable && onClick != null) || super.onInterceptTouchEvent(ev)
    }

    override fun onClick(v: View?) {
        onClick?.takeIf { isSliceViewClickable }?.let { it() } ?: super.onClick(v)
    }
}
