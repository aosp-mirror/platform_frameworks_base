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

package com.android.systemui.qs

import android.view.animation.Interpolator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QSExpansionPathInterpolator @Inject constructor() {

    private var pathInterpolatorBuilder = PathInterpolatorBuilder(0f, 0f, 0f, 1f)
    private var lastX = 0f
    val xInterpolator: Interpolator
        get() = pathInterpolatorBuilder.xInterpolator

    val yInterpolator: Interpolator
        get() = pathInterpolatorBuilder.yInterpolator

    fun setControlX2(value: Float) {
        if (value != lastX) {
            lastX = value
            pathInterpolatorBuilder = PathInterpolatorBuilder(0f, 0f, lastX, 1f)
        }
    }
}