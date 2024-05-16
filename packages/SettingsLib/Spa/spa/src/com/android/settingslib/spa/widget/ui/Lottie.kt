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

package com.android.settingslib.spa.widget.ui

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.android.settingslib.color.R

@Composable
fun Lottie(
    resId: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
    ) {
        BaseLottie(resId)
    }
}

object LottieColorUtils {
    private val DARK_TO_LIGHT_THEME_COLOR_MAP = mapOf(
        ".grey200" to R.color.settingslib_color_grey800,
        ".grey600" to R.color.settingslib_color_grey400,
        ".grey800" to R.color.settingslib_color_grey300,
        ".grey900" to R.color.settingslib_color_grey50,
        ".red400" to R.color.settingslib_color_red600,
        ".black" to android.R.color.white,
        ".blue400" to R.color.settingslib_color_blue600,
        ".green400" to R.color.settingslib_color_green600,
        ".green200" to R.color.settingslib_color_green500,
        ".red200" to R.color.settingslib_color_red500,
    )

    @Composable
    private fun getDefaultPropertiesList() =
        DARK_TO_LIGHT_THEME_COLOR_MAP.map { (key, colorRes) ->
            val color = colorResource(colorRes).toArgb()
            rememberLottieDynamicProperty(
                property = LottieProperty.COLOR_FILTER,
                keyPath = arrayOf("**", key, "**")
            ){ PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP) }
        }

    @Composable
    fun getDefaultDynamicProperties() =
        rememberLottieDynamicProperties(*getDefaultPropertiesList().toTypedArray())
}

@Composable
private fun BaseLottie(resId: Int) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(resId)
    )
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = LottieConstants.IterateForever,
    )
    val isLightMode = !isSystemInDarkTheme()
    LottieAnimation(
        composition = composition,
        dynamicProperties = LottieColorUtils.getDefaultDynamicProperties().takeIf { isLightMode },
        progress = { progress },
    )
}
