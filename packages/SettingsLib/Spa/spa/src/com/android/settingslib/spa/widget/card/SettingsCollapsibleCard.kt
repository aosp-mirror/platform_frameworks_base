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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.ui.ExpandIcon
import com.android.settingslib.spa.widget.ui.SettingsDialogItem
import com.android.settingslib.spa.widget.ui.SettingsTitleSmall

@Composable
fun SettingsCollapsibleCard(
    title: String,
    imageVector: ImageVector,
    models: List<CardModel>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsCard {
        SettingsCardContent {
            Header(title, imageVector, models.count { it.isVisible() }, expanded) { expanded = it }
        }
        AnimatedVisibility(expanded) {
            Column {
                for (model in models) {
                    SettingsCardImpl(model)
                }
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    imageVector: ImageVector,
    cardCount: Int,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { setExpanded(!expanded) }
            .padding(
                horizontal = SettingsDimension.itemPaddingStart,
                vertical = SettingsDimension.itemPaddingVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(SettingsDimension.itemPaddingStart),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(SettingsDimension.itemIconSize),
            tint = MaterialTheme.colorScheme.primary,
        )
        Box(modifier = Modifier.weight(1f)) {
            SettingsTitleSmall(title, useMediumWeight = true)
        }
        CardCount(cardCount, expanded)
    }
}

@Composable
private fun CardCount(modelSize: Int, expanded: Boolean) {
    Surface(
        shape = SettingsShape.CornerExtraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(SettingsDimension.paddingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.padding(SettingsDimension.paddingSmall))
            SettingsDialogItem(modelSize.toString())
            ExpandIcon(expanded)
        }
    }
}

@UiModePreviews
@Composable
private fun SettingsCollapsibleCardPreview() {
    SettingsTheme {
        SettingsCollapsibleCard(
            title = "More alerts",
            imageVector = Icons.Outlined.Error,
            models = listOf(
                CardModel(
                    title = "Lorem ipsum",
                    text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
                    imageVector = Icons.Outlined.PowerOff,
                    buttons = listOf(
                        CardButton(text = "Action") {},
                    )
                ),
                CardModel(
                    title = "Lorem ipsum",
                    text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
                    imageVector = Icons.Outlined.Shield,
                    buttons = listOf(
                        CardButton(text = "Action") {},
                        CardButton(text = "Main action", isMain = true) {},
                    )
                )
            )
        )
    }
}
