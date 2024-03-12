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

package com.android.settingslib.spa.widget.button

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.divider
import androidx.compose.material.icons.automirrored.outlined.Launch

data class ActionButton(
    val text: String,
    val imageVector: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
fun ActionButtons(actionButtons: List<ActionButton>) {
    Row(
        Modifier
            .padding(SettingsDimension.buttonPadding)
            .clip(SettingsShape.CornerExtraLarge)
            .height(IntrinsicSize.Min)
    ) {
        for ((index, actionButton) in actionButtons.withIndex()) {
            if (index > 0) ButtonDivider()
            ActionButton(actionButton)
        }
    }
}

@Composable
private fun RowScope.ActionButton(actionButton: ActionButton) {
    FilledTonalButton(
        onClick = actionButton.onClick,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        enabled = actionButton.enabled,
        // Because buttons could appear, disappear or change positions, reset the interaction source
        // to prevent highlight the wrong button.
        interactionSource = remember(actionButton) { MutableInteractionSource() },
        shape = RectangleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = SettingsTheme.colorScheme.surface,
            contentColor = SettingsTheme.colorScheme.categoryTitle,
            disabledContainerColor = SettingsTheme.colorScheme.surface,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 20.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = actionButton.imageVector,
                contentDescription = null,
                modifier = Modifier.size(SettingsDimension.itemIconSize),
            )
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionButton.text,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ButtonDivider() {
    Box(
        Modifier
            .width(1.dp)
            .background(color = MaterialTheme.colorScheme.divider)
    )
}

@Preview
@Composable
private fun ActionButtonsPreview() {
    SettingsTheme {
        ActionButtons(
            listOf(
                ActionButton(text = "Open", imageVector = Icons.AutoMirrored.Outlined.Launch) {},
                ActionButton(text = "Uninstall", imageVector = Icons.Outlined.Delete) {},
                ActionButton(text = "Force stop", imageVector = Icons.Outlined.WarningAmber) {},
            )
        )
    }
}
