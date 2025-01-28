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

package com.android.systemui.statusbar.chips.ui.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.rememberChronometerState

@Composable
fun ChipContent(viewModel: OngoingActivityChipModel.Shown, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isTextOnly = viewModel.icon == null
    val hasEmbeddedIcon =
        viewModel.icon is OngoingActivityChipModel.ChipIcon.StatusBarView ||
            viewModel.icon is OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon
    val startPadding =
        if (isTextOnly || hasEmbeddedIcon) {
            0.dp
        } else {
            dimensionResource(id = R.dimen.ongoing_activity_chip_icon_text_padding)
        }
    val endPadding =
        if (hasEmbeddedIcon) {
            dimensionResource(
                id = R.dimen.ongoing_activity_chip_text_end_padding_for_embedded_padding_icon
            )
        } else {
            0.dp
        }
    val textStyle = MaterialTheme.typography.labelLarge
    val textColor = Color(viewModel.colors.text(context))
    when (viewModel) {
        is OngoingActivityChipModel.Shown.Timer -> {
            val timerState = rememberChronometerState(startTimeMillis = viewModel.startTimeMs)
            Text(
                text = timerState.currentTimeText,
                style = textStyle,
                color = textColor,
                modifier =
                    modifier.padding(start = startPadding, end = endPadding).neverDecreaseWidth(),
            )
        }

        is OngoingActivityChipModel.Shown.Countdown -> {
            ChipText(
                text = viewModel.secondsUntilStarted.toString(),
                style = textStyle,
                color = textColor,
                modifier =
                    modifier.padding(start = startPadding, end = endPadding).neverDecreaseWidth(),
                backgroundColor = Color(viewModel.colors.background(context).defaultColor),
            )
        }

        is OngoingActivityChipModel.Shown.Text -> {
            ChipText(
                text = viewModel.text,
                style = textStyle,
                color = textColor,
                modifier = modifier.padding(start = startPadding, end = endPadding),
                backgroundColor = Color(viewModel.colors.background(context).defaultColor),
            )
        }

        is OngoingActivityChipModel.Shown.ShortTimeDelta -> {
            // TODO(b/372657935): Implement ShortTimeDelta content in compose.
        }

        is OngoingActivityChipModel.Shown.IconOnly -> {
            throw IllegalStateException("ChipContent should only be used if the chip shows text")
        }
    }
}

/** A modifier that ensures the width of the content only increases and never decreases. */
private fun Modifier.neverDecreaseWidth(): Modifier {
    return this.then(neverDecreaseWidthElement)
}

private data object neverDecreaseWidthElement : ModifierNodeElement<NeverDecreaseWidthNode>() {
    override fun create(): NeverDecreaseWidthNode {
        return NeverDecreaseWidthNode()
    }

    override fun update(node: NeverDecreaseWidthNode) {
        error("This should never be called")
    }
}

private class NeverDecreaseWidthNode : Modifier.Node(), LayoutModifierNode {
    private var minWidth = 0

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(Constraints(minWidth = minWidth).constrain(constraints))
        val width = placeable.width
        val height = placeable.height

        minWidth = maxOf(minWidth, width)

        return layout(width, height) { placeable.place(0, 0) }
    }
}
