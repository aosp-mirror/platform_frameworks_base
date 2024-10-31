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

package com.android.settingslib.spa.widget.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.toSemiBoldWeight

data class SuggestionCardModel(
    val title: String,
    val description: String? = null,
    val imageVector: ImageVector,

    /**
     * A dismiss button will be displayed if this is not null.
     *
     * And this callback will be called when user clicks the button.
     */
    val onDismiss: (() -> Unit)? = null,
    val isVisible: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun SuggestionCard(model: SuggestionCardModel) {
    AnimatedVisibility(visible = model.isVisible) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.padding(
                        horizontal = SettingsDimension.paddingLarge,
                        vertical = SettingsDimension.paddingSmall,
                    )
                    .fillMaxWidth()
                    .heightIn(min = SettingsDimension.preferenceMinHeight)
                    .clip(SettingsShape.CornerExtraLarge1)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .then(model.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    .padding(SettingsDimension.paddingExtraSmall6),
        ) {
            SuggestionCardIcon(model.imageVector)
            Spacer(Modifier.padding(SettingsDimension.paddingSmall))
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                SuggestionCardTitle(model.title)
                if (model.description != null) SuggestionCardDescription(model.description)
            }
            if (model.onDismiss != null) {
                Spacer(Modifier.padding(SettingsDimension.paddingSmall))
                SuggestionCardDismissButton(model.onDismiss)
            }
        }
    }
}

@Composable
private fun SuggestionCardIcon(imageVector: ImageVector) {
    Box(
        modifier =
            Modifier.padding(SettingsDimension.paddingSmall)
                .size(SettingsDimension.itemIconContainerSizeSmall)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(SettingsDimension.itemIconSize),
            tint = MaterialTheme.colorScheme.onSecondary,
        )
    }
}

@Composable
private fun SuggestionCardTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.toSemiBoldWeight(),
        modifier = Modifier.padding(vertical = SettingsDimension.paddingTiny),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SuggestionCardDescription(description: String) {
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmallEmphasized,
        modifier = Modifier.padding(vertical = SettingsDimension.paddingTiny),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun SuggestionCardDismissButton(onDismiss: () -> Unit) {
    IconButton(shape = CircleShape, onClick = onDismiss) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription =
                stringResource(androidx.compose.material3.R.string.m3c_snackbar_dismiss),
            modifier = Modifier.size(SettingsDimension.itemIconSize),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Preview
@Composable
private fun SuggestionCardPreview() {
    SettingsTheme {
        SuggestionCard(
            SuggestionCardModel(
                title = "Suggestion card",
                description = "Suggestion card description",
                imageVector = Icons.Outlined.Stars,
                onDismiss = {},
                onClick = {},
            )
        )
    }
}
