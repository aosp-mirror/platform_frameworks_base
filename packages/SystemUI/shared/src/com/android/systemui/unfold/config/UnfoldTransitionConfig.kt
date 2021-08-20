/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.unfold.config

import android.annotation.IntDef

interface UnfoldTransitionConfig {
    val isEnabled: Boolean

    @AnimationMode
    val mode: Int
}

@IntDef(prefix = ["ANIMATION_MODE_"], value = [
    ANIMATION_MODE_DISABLED,
    ANIMATION_MODE_FIXED_TIMING,
    ANIMATION_MODE_HINGE_ANGLE
])

@Retention(AnnotationRetention.SOURCE)
annotation class AnimationMode

const val ANIMATION_MODE_DISABLED = 0
const val ANIMATION_MODE_FIXED_TIMING = 1
const val ANIMATION_MODE_HINGE_ANGLE = 2
