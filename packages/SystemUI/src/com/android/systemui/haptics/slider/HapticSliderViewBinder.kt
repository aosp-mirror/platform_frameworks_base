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

package com.android.systemui.haptics.slider

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.awaitCancellation

object HapticSliderViewBinder {
    /**
     * Binds a [SeekableSliderHapticPlugin] to a [View]. The binded view should be a
     * [android.widget.SeekBar] or a container of a [android.widget.SeekBar]
     */
    @JvmStatic
    fun bind(view: View?, plugin: SeekableSliderHapticPlugin) {
        view?.repeatWhenAttached {
            plugin.startInScope(lifecycleScope)
            try {
                awaitCancellation()
            } finally {
                plugin.stop()
            }
        }
    }
}
