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

package com.android.systemui.qs

import android.content.Context
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo
import com.android.systemui.res.R
import com.android.systemui.util.DelayableMarqueeTextView

class BuildTextView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : DelayableMarqueeTextView(context, attrs, defStyleAttr, defStyleRes) {

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Clear selected state so it's not announced by accessibility, but we can still marquee.
        info?.isSelected = false
        info?.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                resources.getString(R.string.copy_to_clipboard_a11y_action),
            )
        )
    }
}
