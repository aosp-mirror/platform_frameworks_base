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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.divider

data class ActionButton(
    val text: String,
    val imageVector: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun ActionButtons(actionButtons: List<ActionButton>) {
    Row(
        Modifier
            .padding(SettingsDimension.itemPaddingVertical)
            .clip(SettingsShape.CornerLarge)
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
        shape = RectangleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = SettingsTheme.colorScheme.categoryTitle,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 20.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = actionButton.imageVector,
                contentDescription = null,
                modifier = Modifier.size(SettingsDimension.itemIconSize),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = actionButton.text,
                style = MaterialTheme.typography.labelLarge,
            )
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
                ActionButton(text = "Open", imageVector = Icons.Outlined.Launch) {},
                ActionButton(text = "Uninstall", imageVector = Icons.Outlined.Delete) {},
                ActionButton(text = "Force stop", imageVector = Icons.Outlined.WarningAmber) {},
            )
        )
    }
}
