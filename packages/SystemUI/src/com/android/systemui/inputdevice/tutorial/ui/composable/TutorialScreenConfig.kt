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

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.airbnb.lottie.compose.LottieDynamicProperties

data class TutorialScreenConfig(
    val colors: Colors,
    val strings: Strings,
    val animations: Animations
) {

    data class Colors(
        val background: Color,
        val title: Color,
        val animationColors: LottieDynamicProperties
    )

    data class Strings(
        @StringRes val titleResId: Int,
        @StringRes val bodyResId: Int,
        @StringRes val titleSuccessResId: Int,
        @StringRes val bodySuccessResId: Int,
    )

    data class Animations(
        @RawRes val educationResId: Int,
        @RawRes val successResId: Int,
    )
}
