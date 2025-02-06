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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.featurepods.popups.shared.model.HoverBehavior
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipModel

/**
 * A clickable chip that can show an anchored popup containing relevant system controls. The chip
 * can show an icon that can have its own separate action distinct from its parent chip. Moreover,
 * the chip can show text containing contextual information.
 */
@Composable
fun StatusBarPopupChip(model: PopupChipModel.Shown, modifier: Modifier = Modifier) {
    val hasHoverBehavior = model.hoverBehavior !is HoverBehavior.None
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val isToggled = model.isToggled

    val chipBackgroundColor =
        if (isToggled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier =
            modifier
                .widthIn(max = 120.dp)
                .padding(vertical = 4.dp)
                .animateContentSize()
                .thenIf(hasHoverBehavior) { Modifier.hoverable(hoverInteractionSource) }
                .clickable { model.onToggle() },
        color = chipBackgroundColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val iconColor =
                if (isHovered) chipBackgroundColor else contentColorFor(chipBackgroundColor)
            val hoverBehavior = model.hoverBehavior
            val iconBackgroundColor = contentColorFor(chipBackgroundColor)
            val iconInteractionSource = remember { MutableInteractionSource() }
            Icon(
                icon =
                    when {
                        isHovered && hoverBehavior is HoverBehavior.Button -> hoverBehavior.icon
                        else -> model.icon
                    },
                modifier =
                    Modifier.thenIf(isHovered) {
                            Modifier.padding(3.dp)
                                .background(color = iconBackgroundColor, shape = CircleShape)
                        }
                        .thenIf(hoverBehavior is HoverBehavior.Button) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClick = (hoverBehavior as HoverBehavior.Button).onIconPressed,
                                indication = ripple(),
                                interactionSource = iconInteractionSource,
                            )
                        }
                        .padding(3.dp),
                tint = iconColor,
            )

            Text(
                text = model.chipText,
                style = MaterialTheme.typography.labelLarge,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
