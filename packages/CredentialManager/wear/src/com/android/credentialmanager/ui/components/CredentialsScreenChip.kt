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
package com.android.credentialmanager.ui.components

import androidx.wear.compose.material.MaterialTheme as WearMaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.core.graphics.drawable.toBitmap
import androidx.wear.compose.material.ChipColors
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.ChipDefaults
import com.android.credentialmanager.R
import com.android.credentialmanager.common.ui.components.WearButtonText
import com.android.credentialmanager.common.ui.components.WearSecondaryLabel
import com.android.credentialmanager.model.get.AuthenticationEntryInfo

/* Used as credential suggestion or user action chip. */
@Composable
fun CredentialsScreenChip(
    primaryText: @Composable () -> Unit,
    secondaryText: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    icon: Drawable? = null,
    isAuthenticationEntryLocked: Boolean? = null,
    modifier: Modifier = Modifier,
    colors: ChipColors = ChipDefaults.secondaryChipColors()
) {
    val labelParam: (@Composable RowScope.() -> Unit) =
        {
            var horizontalArrangement = Arrangement.Start
            if (icon == null) {
                horizontalArrangement = Arrangement.Center
            }
            Row(horizontalArrangement = horizontalArrangement, modifier = modifier.fillMaxWidth()) {
                primaryText()
            }
        }

    val secondaryLabelParam: (@Composable RowScope.() -> Unit)? =
        secondaryText?.let {
            {
                Row {
                    secondaryText()
                    if (isAuthenticationEntryLocked != null) {
                        if (isAuthenticationEntryLocked) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp).align(Alignment.CenterVertically),
                                tint = WearMaterialTheme.colors.onSurfaceVariant
                            )
                        } else {
                            Icon(
                                Icons.Outlined.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp).align(Alignment.CenterVertically),
                                tint = WearMaterialTheme.colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

    val iconParam: (@Composable BoxScope.() -> Unit)? =
        icon?.toBitmap()?.asImageBitmap()?.let {
            {
                Icon(
                    bitmap = it,
                    // Decorative purpose only.
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.Unspecified
                )
            }
        }

    Chip(
        label = labelParam,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        secondaryLabel = secondaryLabelParam,
        icon = iconParam,
        colors = colors,
        enabled = true,
    )
}

@Preview
@Composable
fun CredentialsScreenChipPreview() {
    CredentialsScreenChip(
        primaryText = {
            WearButtonText(
                text = "Elisa Beckett",
                textAlign = TextAlign.Start,
            )
        },
        onClick = { },
        secondaryText = {
            WearSecondaryLabel(
                text = "beckett_bakery@gmail.com",
                color = WearMaterialTheme.colors.onSurfaceVariant
            )
        },
        icon = null,
    )
}

@Composable
fun SignInOptionsChip(onClick: () -> Unit) {
    CredentialsScreenChip(
        primaryText = {
            WearButtonText(
                text = stringResource(R.string.dialog_sign_in_options_button),
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        },
        onClick = onClick,
    )
}

@Preview
@Composable
fun SignInOptionsChipPreview() {
    SignInOptionsChip({})
}

@Composable
fun ContinueChip(onClick: () -> Unit) {
    CredentialsScreenChip(
        onClick = onClick,
        primaryText = {
            WearButtonText(
                text = stringResource(R.string.dialog_continue_button),
                textAlign = TextAlign.Center,
                color = WearMaterialTheme.colors.surface,
            )
        },
        colors = ChipDefaults.primaryChipColors(),
    )
}

@Preview
@Composable
fun ContinueChipPreview() {
    ContinueChip({})
}

@Composable
fun DismissChip(onClick: () -> Unit) {
    CredentialsScreenChip(
        primaryText = {
            WearButtonText(
                text = stringResource(R.string.dialog_dismiss_button),
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        },
        onClick = onClick,
    )
}
@Composable
fun LockedProviderChip(
    authenticationEntryInfo: AuthenticationEntryInfo,
    secondaryMaxLines: Int = 1,
    onClick: () -> Unit,
) {
    val secondaryLabel = stringResource(
        if (authenticationEntryInfo.isUnlockedAndEmpty)
            R.string.locked_credential_entry_label_subtext_no_sign_in
        else R.string.locked_credential_entry_label_subtext_tap_to_unlock
    )

    CredentialsScreenChip(
        primaryText = {
            WearButtonText(
                text = authenticationEntryInfo.title,
                textAlign = TextAlign.Start,
                maxLines = 2,
            )
        },
        icon = authenticationEntryInfo.icon,
        secondaryText = {
            WearSecondaryLabel(
                text = secondaryLabel,
                color = WearMaterialTheme.colors.onSurfaceVariant,
                maxLines = secondaryMaxLines
                )
        },
        isAuthenticationEntryLocked = !authenticationEntryInfo.isUnlockedAndEmpty,
        onClick = onClick,
    )
}

@Preview
@Composable
fun DismissChipPreview() {
    DismissChip({})
}

