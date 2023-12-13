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

package com.android.settingslib.spa.widget.illustration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.ui.ImageBox
import com.android.settingslib.spa.widget.ui.Lottie
enum class ResourceType { IMAGE, LOTTIE }

/**
 * The widget model for [Illustration] widget.
 */
interface IllustrationModel {
    /**
     * The resource id of this [Illustration].
     */
    val resId: Int

    /**
     * The resource type of the [Illustration].
     *
     * It should be Lottie or Image.
     */
    val resourceType: ResourceType
}

/**
 * Illustration widget.
 *
 * Data is provided through [IllustrationModel].
 */
@Composable
fun Illustration(model: IllustrationModel) {
    Illustration(
        resId = model.resId,
        resourceType = model.resourceType,
        modifier = Modifier,
    )
}

@Composable
fun Illustration(
    resId: Int,
    resourceType: ResourceType,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsDimension.illustrationPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val illustrationModifier = modifier
            .sizeIn(
                maxWidth = SettingsDimension.illustrationMaxWidth,
                maxHeight = SettingsDimension.illustrationMaxHeight,
            )
            .clip(RoundedCornerShape(SettingsDimension.illustrationCornerRadius))
            .background(color = Color.Transparent)

        when (resourceType) {
            ResourceType.LOTTIE -> {
                Lottie(
                    resId = resId,
                    modifier = illustrationModifier,
                )
            }
            ResourceType.IMAGE -> {
                ImageBox(
                    resId = resId,
                    contentDescription = null,
                    modifier = illustrationModifier,
                )
            }
        }
    }
}
