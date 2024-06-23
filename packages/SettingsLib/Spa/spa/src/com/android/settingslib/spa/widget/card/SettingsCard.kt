/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spa.widget.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape.CornerExtraLarge
import com.android.settingslib.spa.framework.theme.SettingsShape.CornerExtraSmall
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsTitle

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = CornerExtraLarge,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsDimension.itemPaddingEnd,
                vertical = SettingsDimension.itemPaddingAround,
            ),
        content = content,
    )
}

@Composable
fun SettingsCardContent(
    containerColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = CornerExtraSmall,
        colors = CardDefaults.cardColors(
            containerColor = containerColor.takeOrElse { SettingsTheme.colorScheme.surface },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        content = content,
    )
}

@Composable
fun SettingsCard(model: CardModel) {
    SettingsCard {
        SettingsCardImpl(model)
    }
}

@Composable
internal fun SettingsCardImpl(model: CardModel) {
    AnimatedVisibility(visible = model.isVisible()) {
        SettingsCardContent(containerColor = model.containerColor) {
            Column(
                modifier = (model.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    .padding(
                        horizontal = SettingsDimension.dialogItemPaddingHorizontal,
                        vertical = SettingsDimension.itemPaddingAround,
                    ),
                verticalArrangement = Arrangement.spacedBy(SettingsDimension.itemPaddingAround)
            ) {
                CardHeader(model.imageVector, model.tintColor, model.onDismiss)
                SettingsTitle(model.title)
                SettingsBody(model.text)
                Buttons(model.buttons, model.tintColor)
            }
        }
    }
}

@Composable
fun CardHeader(imageVector: ImageVector?, iconColor: Color, onDismiss: (() -> Unit)? = null) {
    if (imageVector != null || onDismiss != null) {
        Spacer(Modifier.height(SettingsDimension.buttonPaddingVertical))
    }
    Row(Modifier.fillMaxWidth()) {
        CardIcon(imageVector, iconColor)
        Spacer(modifier = Modifier.weight(1f))
        DismissButton(onDismiss)
    }
}

@Composable
private fun CardIcon(imageVector: ImageVector?, color: Color) {
    if (imageVector != null) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(SettingsDimension.itemIconSize),
            tint = color.takeOrElse { MaterialTheme.colorScheme.primary },
        )
    }
}

@Composable
private fun DismissButton(onDismiss: (() -> Unit)?) {
    if (onDismiss == null) return
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(SettingsDimension.itemIconSize)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    androidx.compose.material3.R.string.m3c_snackbar_dismiss
                ),
                modifier = Modifier.padding(SettingsDimension.paddingSmall),
            )
        }
    }
}

@Composable
private fun Buttons(buttons: List<CardButton>, color: Color) {
    if (buttons.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                space = SettingsDimension.itemPaddingEnd,
                alignment = Alignment.End,
            ),
        ) {
            for (button in buttons) {
                Button(button, color)
            }
        }
    } else {
        Spacer(Modifier.height(SettingsDimension.itemPaddingAround))
    }
}

@Composable
private fun Button(button: CardButton, color: Color) {
    TextButton(
        onClick = button.onClick,
        modifier =
            Modifier.semantics { button.contentDescription?.let { this.contentDescription = it } }
    ) {
        Text(text = button.text, color = color)
    }
}

@UiModePreviews
@Composable
private fun SettingsCardPreview() {
    SettingsTheme {
        SettingsCard(
            CardModel(
                title = "Lorem ipsum",
                text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
                imageVector = Icons.Outlined.WarningAmber,
                buttons = listOf(
                    CardButton(text = "Action") {},
                )
            )
        )
    }
}
