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
import androidx.compose.foundation.layout.size
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
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipModel

/**
 * A clickable chip that can show an anchored popup containing relevant system controls. The chip
 * can show an icon that can have its own separate action distinct from its parent chip. Moreover,
 * the chip can show text containing contextual information.
 */
@Composable
fun StatusBarPopupChip(model: PopupChipModel.Shown, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isToggled = model.isToggled

    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier =
            modifier
                .hoverable(interactionSource = interactionSource)
                .padding(vertical = 4.dp)
                .widthIn(max = 120.dp)
                .animateContentSize()
                .clickable(onClick = { model.onToggle() }),
        color =
            if (isToggled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val currentIcon = if (isHovered) model.hoverIcon else model.icon
            val backgroundColor =
                if (isToggled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }

            Icon(
                icon = currentIcon,
                modifier =
                    Modifier.background(color = backgroundColor, shape = CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClick = model.onIconPressed,
                            indication = ripple(),
                            interactionSource = remember { MutableInteractionSource() },
                        )
                        .padding(2.dp)
                        .size(18.dp),
                tint = contentColorFor(backgroundColor),
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
