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
package com.android.credentialmanager.ui.screens.multiple

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import com.android.credentialmanager.ui.components.CredentialsScreenChip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.android.credentialmanager.CredentialSelectorUiState.Get.MultipleEntry
import com.android.credentialmanager.FlowEngine
import com.android.credentialmanager.R
import com.android.credentialmanager.common.ui.components.WearButtonText
import com.android.credentialmanager.ui.components.LockedProviderChip
import com.android.credentialmanager.common.ui.components.WearSecondaryLabel
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.ui.components.CredentialsScreenChipSpacer
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberColumnState
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.MaterialTheme as WearMaterialTheme

/**
 * Screen that shows multiple credentials to select from, grouped by accounts
 *
 * @param credentialSelectorUiState The app bar view model.
 * @param modifier styling for composable
 * @param flowEngine [FlowEngine] that updates ui state for this screen
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun MultiCredentialsFlattenScreen(
    credentialSelectorUiState: MultipleEntry,
    flowEngine: FlowEngine,
) {
    val selectEntry = flowEngine.getEntrySelector()
    Row {
        Spacer(Modifier.weight(0.052f)) // 5.2% side margin
        ScalingLazyColumn(
            columnState = rememberColumnState(
                ScalingLazyColumnDefaults.belowTimeText(horizontalAlignment = Alignment.Start),
            ),
            modifier = Modifier.weight(0.896f).fillMaxSize(), // 5.2% side margin
        ) {

        item {
            Row {
                Spacer(Modifier.weight(0.073f)) // 7.3% side margin
                WearButtonText(
                    text = stringResource(R.string.sign_in_options_title),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(0.854f).fillMaxSize(),
                    maxLines = 2,
                )
                Spacer(Modifier.weight(0.073f)) // 7.3% side margin
            }
        }

        credentialSelectorUiState.accounts.forEach { userNameEntries ->
            item {
                Row {
                    Spacer(Modifier.weight(0.0624f)) // 6.24% side margin
                    WearSecondaryLabel(
                        text = userNameEntries.name,
                        modifier = Modifier.padding(
                            top = 12.dp,
                            bottom = 4.dp,
                            start = 0.dp,
                            end = 0.dp
                        ).fillMaxWidth(0.87f)
                    )
                    Spacer(Modifier.weight(0.0624f)) // 6.24% side margin
                }
            }

            userNameEntries.sortedCredentialEntryList.forEach { credential: CredentialEntryInfo ->
                item {
                    CredentialsScreenChip(
                        primaryText = {
                            WearButtonText(
                                text = credential.userName,
                                textAlign = TextAlign.Start,
                                maxLines = 2,
                            )
                        },
                        onClick = { selectEntry(credential, false) },
                        secondaryText =
                        {
                            WearSecondaryLabel(
                                text = credential.credentialTypeDisplayName.ifEmpty {
                                    credential.providerDisplayName
                                },
                                color = WearMaterialTheme.colors.onSurfaceVariant,
                                maxLines = 2
                            )
                        },
                        icon = credential.icon,
                    )

                    CredentialsScreenChipSpacer()
                }
            }

            credentialSelectorUiState.authenticationEntryList.forEach { authenticationEntryInfo ->
                item {
                    LockedProviderChip(authenticationEntryInfo, secondaryMaxLines = 2) {
                        selectEntry(authenticationEntryInfo, false)
                    }
                    CredentialsScreenChipSpacer()
                }
            }
        }

        if (credentialSelectorUiState.actionEntryList.isNotEmpty()) {
            item {
                Row {
                    Spacer(Modifier.weight(0.0624f)) // 6.24% side margin
                    WearSecondaryLabel(
                        text = stringResource(R.string.provider_list_title),
                        modifier = Modifier.padding(
                            top = 12.dp,
                            bottom = 4.dp,
                            start = 0.dp,
                            end = 0.dp
                        ).fillMaxWidth(0.87f),
                        maxLines = 2
                )
                    Spacer(Modifier.weight(0.0624f)) // 6.24% side margin
                }
            }
            credentialSelectorUiState.actionEntryList.forEach { actionEntry ->
                item {
                    CredentialsScreenChip(
                        primaryText = {
                            WearButtonText(
                                text = actionEntry.title,
                                textAlign = TextAlign.Start,
                                maxLines = 2
                            )
                        },
                        onClick = { selectEntry(actionEntry, false) },
                        secondaryText = null,
                        icon = actionEntry.icon,
                    )
                    CredentialsScreenChipSpacer()
                }
            }
        }
    }
    Spacer(Modifier.weight(0.052f)) // 5.2% side margin
    }
}
