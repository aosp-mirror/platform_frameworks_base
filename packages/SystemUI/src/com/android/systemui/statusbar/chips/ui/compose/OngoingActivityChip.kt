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

package com.android.systemui.statusbar.chips.ui.compose

import android.content.res.ColorStateList
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.Expandable
import com.android.compose.modifiers.thenIf
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel

@Composable
fun OngoingActivityChip(model: OngoingActivityChipModel.Active, modifier: Modifier = Modifier) {
    when (val clickBehavior = model.clickBehavior) {
        is OngoingActivityChipModel.ClickBehavior.ExpandAction -> {
            // Wrap the chip in an Expandable so we can animate the expand transition.
            ExpandableChip(
                color = { Color.Transparent },
                shape =
                    RoundedCornerShape(
                        dimensionResource(id = R.dimen.ongoing_activity_chip_corner_radius)
                    ),
                modifier = modifier,
            ) { expandable ->
                ChipBody(model, onClick = { clickBehavior.onClick(expandable) })
            }
        }
        is OngoingActivityChipModel.ClickBehavior.ShowHeadsUpNotification -> {
            ChipBody(model, onClick = { clickBehavior.onClick() })
        }

        is OngoingActivityChipModel.ClickBehavior.None -> {
            ChipBody(model, modifier = modifier)
        }
    }
}

@Composable
private fun ChipBody(
    model: OngoingActivityChipModel.Active,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val isClickable = onClick != null
    val hasEmbeddedIcon = model.icon is OngoingActivityChipModel.ChipIcon.StatusBarView
    val contentDescription =
        when (val icon = model.icon) {
            is OngoingActivityChipModel.ChipIcon.StatusBarView -> icon.contentDescription.load()
            is OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon ->
                icon.contentDescription.load()
            is OngoingActivityChipModel.ChipIcon.SingleColorIcon -> null
            null -> null
        }
    val chipSidePadding = dimensionResource(id = R.dimen.ongoing_activity_chip_side_padding)
    val minWidth =
        if (isClickable) {
            dimensionResource(id = R.dimen.min_clickable_item_size)
        } else if (model.icon != null) {
            dimensionResource(id = R.dimen.ongoing_activity_chip_icon_size) + chipSidePadding
        } else {
            dimensionResource(id = R.dimen.ongoing_activity_chip_min_text_width) + chipSidePadding
        }
    // Use a Box with `fillMaxHeight` to create a larger click surface for the chip. The visible
    // height of the chip is determined by the height of the background of the Row below.
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(enabled = isClickable, onClick = onClick ?: {})
                .semantics {
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                },
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.clip(
                        RoundedCornerShape(
                            dimensionResource(id = R.dimen.ongoing_activity_chip_corner_radius)
                        )
                    )
                    .height(dimensionResource(R.dimen.ongoing_appops_chip_height))
                    .thenIf(isClickable) { Modifier.widthIn(min = minWidth) }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (constraints.maxWidth >= minWidth.roundToPx()) {
                                placeable.place(0, 0)
                            }
                        }
                    }
                    .background(Color(model.colors.background(context).defaultColor))
                    .padding(
                        horizontal =
                            if (hasEmbeddedIcon) {
                                0.dp
                            } else {
                                dimensionResource(id = R.dimen.ongoing_activity_chip_side_padding)
                            }
                    ),
        ) {
            model.icon?.let { ChipIcon(viewModel = it, colors = model.colors) }

            val isIconOnly = model is OngoingActivityChipModel.Active.IconOnly
            if (!isIconOnly) {
                ChipContent(viewModel = model)
            }
        }
    }
}

@Composable
private fun ChipIcon(
    viewModel: OngoingActivityChipModel.ChipIcon,
    colors: ColorsModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    when (viewModel) {
        is OngoingActivityChipModel.ChipIcon.StatusBarView -> {
            // TODO(b/364653005): If the notification updates their small icon, ensure it's updated
            // in the chip.
            val originalIcon = viewModel.impl
            val iconSizePx =
                context.resources.getDimensionPixelSize(
                    R.dimen.ongoing_activity_chip_embedded_padding_icon_size
                )
            AndroidView(
                modifier = modifier,
                factory = { _ ->
                    originalIcon.apply {
                        layoutParams = ViewGroup.LayoutParams(iconSizePx, iconSizePx)
                        imageTintList = ColorStateList.valueOf(colors.text(context))
                    }
                },
            )
        }

        is OngoingActivityChipModel.ChipIcon.SingleColorIcon -> {
            Icon(
                icon = viewModel.impl,
                tint = Color(colors.text(context)),
                modifier =
                    modifier.size(dimensionResource(id = R.dimen.ongoing_activity_chip_icon_size)),
            )
        }

        // TODO(b/372657935): Add recommended architecture implementation for
        // StatusBarNotificationIcons
        is OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon -> {}
    }
}

@Composable
private fun ExpandableChip(
    color: () -> Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(color = color(), shape = shape, modifier = modifier.clip(shape)) { content(it) }
}
