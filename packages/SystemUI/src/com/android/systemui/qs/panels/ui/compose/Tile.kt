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

package com.android.systemui.qs.panels.ui.compose

import android.graphics.drawable.Animatable
import android.text.TextUtils
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import com.android.compose.theme.colorAttr
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import kotlinx.coroutines.delay

@Composable
fun Tile(
    tileViewModel: TileViewModel,
    modifier: Modifier = Modifier,
) {
    val state: TileUiState by tileViewModel.state.collectAsState(tileViewModel.currentState)
    val context = LocalContext.current
    val horizontalAlignment =
        if (state.iconOnly) {
            Alignment.CenterHorizontally
        } else {
            Alignment.Start
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimensionResource(R.dimen.qs_corner_radius)))
                .clickable { tileViewModel.onClick(null) }
                .background(colorAttr(state.colors.background))
                .padding(horizontal = dimensionResource(id = R.dimen.qs_label_container_margin)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.spacedBy(
                space = dimensionResource(id = R.dimen.qs_label_container_margin),
                alignment = horizontalAlignment
            )
    ) {
        val icon =
            remember(state.icon) {
                state.icon.get().let {
                    if (it is QSTileImpl.ResourceIcon) {
                        IconModel.Resource(it.resId, null)
                    } else {
                        IconModel.Loaded(it.getDrawable(context), null)
                    }
                }
            }
        TileIcon(icon, colorAttr(state.colors.icon))

        if (!state.iconOnly) {
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxHeight()) {
                Text(
                    state.label.toString(),
                    color = colorAttr(state.colors.label),
                    modifier = Modifier.basicMarquee(),
                )
                if (!TextUtils.isEmpty(state.secondaryLabel)) {
                    Text(
                        state.secondaryLabel.toString(),
                        color = colorAttr(state.colors.secondaryLabel),
                        modifier = Modifier.basicMarquee(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun TileIcon(icon: IconModel, color: Color) {
    val modifier = Modifier.size(dimensionResource(id = R.dimen.qs_icon_size))
    val context = LocalContext.current
    val loadedDrawable =
        remember(icon, context) {
            when (icon) {
                is IconModel.Loaded -> icon.drawable
                is IconModel.Resource -> AppCompatResources.getDrawable(context, icon.res)
            }
        }
    if (loadedDrawable !is Animatable) {
        Icon(
            icon = icon,
            tint = color,
            modifier = modifier,
        )
    } else if (icon is IconModel.Resource) {
        val image = AnimatedImageVector.animatedVectorResource(id = icon.res)
        var atEnd by remember(icon.res) { mutableStateOf(false) }
        LaunchedEffect(key1 = icon.res) {
            delay(350)
            atEnd = true
        }
        val painter = rememberAnimatedVectorPainter(animatedImageVector = image, atEnd = atEnd)
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(color = color),
            modifier = modifier
        )
    }
}
