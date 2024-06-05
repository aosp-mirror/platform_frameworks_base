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

import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    onWidthChanged: ((Int) -> Unit)? = null,
    enableAccessibility: Boolean = true,
) {
    AndroidView(
        modifier = modifier,
        factory = { context: Context ->
            ComposeSliceView(
                    ContextThemeWrapper(context, R.style.Widget_SliceView_VolumePanel),
                )
                .apply {
                    mode = SliceView.MODE_LARGE
                    isScrollable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setShowTitleItems(true)
                }
        },
        update = { sliceView: ComposeSliceView ->
            sliceView.slice = slice
            sliceView.layoutListener = onWidthChanged?.let(::OnWidthChangedLayoutListener)
            sliceView.enableAccessibility = enableAccessibility
        },
        onRelease = { sliceView: ComposeSliceView ->
            sliceView.layoutListener = null
            sliceView.slice = null
            sliceView.enableAccessibility = true
        },
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

private class ComposeSliceView(context: Context) : SliceView(context) {

    var enableAccessibility: Boolean = true
    var layoutListener: OnLayoutChangeListener? = null
        set(value) {
            field?.let { removeOnLayoutChangeListener(it) }
            field = value
            field?.let { addOnLayoutChangeListener(it) }
        }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        if (enableAccessibility) {
            super.onInitializeAccessibilityNodeInfo(info)
        }
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent?) {
        if (enableAccessibility) {
            super.onInitializeAccessibilityEvent(event)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        return if (enableAccessibility) {
            super.performAccessibilityAction(action, arguments)
        } else {
            false
        }
    }

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>?) {
        if (enableAccessibility) {
            super.addChildrenForAccessibility(outChildren)
        }
    }
}
