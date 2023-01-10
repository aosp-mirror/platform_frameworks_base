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

package com.android.systemui.testing.screenshot

import android.view.View
import android.view.ViewGroup
import com.android.systemui.util.children
import android.view.WindowInsets

/**
 * Elevation/shadows is not deterministic when doing hardware rendering, this exentsion allows to
 * disable it for any view in the hierarchy.
 */
fun View.removeElevationRecursively() {
    this.elevation = 0f
    (this as? ViewGroup)?.children?.forEach(View::removeElevationRecursively)
}

/**
 * Different devices could have different insets (e.g. different height of the navigation bar or
 * taskbar). This method dispatches empty insets to the whole view hierarchy and removes
 * the original listener, so the views won't receive real insets.
 */
fun View.removeInsetsRecursively() {
    this.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
    this.setOnApplyWindowInsetsListener(null)
    (this as? ViewGroup)?.children?.forEach(View::removeInsetsRecursively)
}
