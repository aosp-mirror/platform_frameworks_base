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

package com.android.systemui.inputdevice.tutorial.ui.composable

import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieDynamicProperty
import com.airbnb.lottie.compose.rememberLottieDynamicProperty

@Composable
fun rememberColorFilterProperty(
    layerName: String,
    color: Color,
): LottieDynamicProperty<ColorFilter> {
    return rememberLottieDynamicProperty(
        LottieProperty.COLOR_FILTER,
        value = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_ATOP),
        // "**" below means match zero or more layers, so ** layerName ** means find layer with that
        // name at any depth
        keyPath = arrayOf("**", layerName, "**"),
    )
}
