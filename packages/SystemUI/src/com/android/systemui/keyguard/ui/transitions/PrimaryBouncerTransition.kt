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

package com.android.systemui.keyguard.ui.transitions

import android.util.MathUtils.lerp
import kotlinx.coroutines.flow.Flow

/**
 * Each PrimaryBouncerTransition is responsible for updating various UI states based on the nature
 * of the transition.
 *
 * MUST list implementing classes in dagger module
 * [com.android.systemui.keyguard.dagger.PrimaryBouncerTransitionImplModule].
 */
interface PrimaryBouncerTransition {
    /** Radius of blur applied to the window's root view. */
    val windowBlurRadius: Flow<Float>

    /** Radius of blur applied to the notifications on expanded shade */
    val notificationBlurRadius: Flow<Float>

    fun transitionProgressToBlurRadius(
        starBlurRadius: Float,
        endBlurRadius: Float,
        transitionProgress: Float,
    ): Float = lerp(starBlurRadius, endBlurRadius, transitionProgress)
}
