/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.transitions

import javax.inject.Inject

/** Config that provides the max and min blur radius for the window blurs. */
data class BlurConfig(val minBlurRadiusPx: Float, val maxBlurRadiusPx: Float) {
    // No-op config that will be used by dagger of other SysUI variants which don't blur the
    // background surface.
    @Inject constructor() : this(0.0f, 0.0f)
}
