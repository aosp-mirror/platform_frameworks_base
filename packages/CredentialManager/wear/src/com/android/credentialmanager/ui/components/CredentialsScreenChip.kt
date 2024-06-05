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

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
    label: String,
    onClick: () -> Unit,
    secondaryLabel: String? = null,
    icon: Drawable? = null,
    isAuthenticationEntryLocked: Boolean = false,
    textAlign: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier,
    colors: ChipColors = ChipDefaults.secondaryChipColors()
) {
        return CredentialsScreenChip(
                    onClick,
                    text = {
                        WearButtonText(
                            text = label,
                            textAlign = textAlign,
                            maxLines = if (secondaryLabel != null) 1 else 2
                        )
                    },
                    secondaryLabel,
                    icon,
                    isAuthenticationEntryLocked,
                    modifier,
                    colors
        )
}



/* Used as credential suggestion or user action chip. */
@Composable
fun CredentialsScreenChip(
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    secondaryLabel: String? = null,
    icon: Drawable? = null,
    isAuthenticationEntryLocked: Boolean = false,
    modifier: Modifier = Modifier,
    colors: ChipColors = ChipDefaults.primaryChipColors(),
    ) {
    val labelParam: (@Composable RowScope.() -> Unit) =
        {
            text()
        }

    val secondaryLabelParam: (@Composable RowScope.() -> Unit)? =
        secondaryLabel?.let {
            {
                Row {
                    WearSecondaryLabel(
                        text = secondaryLabel,
                    )

                    if (isAuthenticationEntryLocked)
                    // TODO(b/324465527) change this to lock icon and correct size once figma mocks are
                    // updated
                        Icon(
                            bitmap = checkNotNull(icon?.toBitmap()?.asImageBitmap()),
                            // Decorative purpose only.
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = Color.Unspecified
                        )
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
        label = "Elisa Beckett",
        onClick = { },
        secondaryLabel = "beckett_bakery@gmail.com",
        icon = null,
    )
}

@Composable
fun SignInOptionsChip(onClick: () -> Unit) {
    CredentialsScreenChip(
        label = stringResource(R.string.dialog_sign_in_options_button),
        textAlign = TextAlign.Start,
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
        text = {
            WearButtonText(
                text = stringResource(R.string.dialog_continue_button),
                textAlign = TextAlign.Center,
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
        label = stringResource(R.string.dialog_dismiss_button),
        onClick = onClick,
    )
}
@Composable
fun LockedProviderChip(
    authenticationEntryInfo: AuthenticationEntryInfo,
    onClick: () -> Unit
) {
    val secondaryLabel = stringResource(
        if (authenticationEntryInfo.isUnlockedAndEmpty)
            R.string.locked_credential_entry_label_subtext_no_sign_in
        else R.string.locked_credential_entry_label_subtext_tap_to_unlock
    )

    CredentialsScreenChip(
        label = authenticationEntryInfo.title,
        icon = authenticationEntryInfo.icon,
        secondaryLabel = secondaryLabel,
        textAlign = TextAlign.Start,
        isAuthenticationEntryLocked = !authenticationEntryInfo.isUnlockedAndEmpty,
        onClick = onClick,
    )
}

@Preview
@Composable
fun DismissChipPreview() {
    DismissChip({})
}

