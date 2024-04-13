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

package com.android.systemui.volume

import android.os.Bundle
import android.view.View
import android.view.View.AccessibilityDelegate
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import com.android.internal.R

class VolumeDialogSeekBarAccessibilityDelegate(
    private val accessibilityStep: Int,
) : AccessibilityDelegate() {

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        require(host is SeekBar) { "This class only works with the SeekBar" }
        val seekBar: SeekBar = host
        if (
            action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ) {
            var increment = accessibilityStep
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                increment = -increment
            }

            return super.performAccessibilityAction(
                host,
                R.id.accessibilityActionSetProgress,
                Bundle().apply {
                    putFloat(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                        (seekBar.progress + increment).coerceIn(seekBar.min, seekBar.max).toFloat(),
                    )
                },
            )
        }
        return super.performAccessibilityAction(host, action, args)
    }
}
