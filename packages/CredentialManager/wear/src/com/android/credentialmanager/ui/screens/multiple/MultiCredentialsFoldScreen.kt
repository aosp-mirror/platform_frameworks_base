/*
 * Copyright 2024 The Android Open Source Project
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
import androidx.compose.foundation.layout.fillMaxSize
import com.android.credentialmanager.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.credentialmanager.CredentialSelectorUiState
import com.android.credentialmanager.FlowEngine
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.ui.components.DismissChip
import com.android.credentialmanager.ui.components.CredentialsScreenChip
import com.android.credentialmanager.ui.components.SignInHeader
import com.android.credentialmanager.ui.components.SignInOptionsChip
import com.android.credentialmanager.ui.components.LockedProviderChip
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScalingLazyColumnState
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.ui.components.BottomSpacer
import com.android.credentialmanager.ui.components.CredentialsScreenChipSpacer
import com.android.credentialmanager.common.ui.components.WearButtonText
import com.android.credentialmanager.common.ui.components.WearSecondaryLabel
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.MaterialTheme as WearMaterialTheme

/**
 * Screen that shows multiple credentials to select from.
 *
 * @param credentialSelectorUiState The app bar view model.
 * @param columnState ScalingLazyColumn configuration to be be applied
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun MultiCredentialsFoldScreen(
    credentialSelectorUiState: CredentialSelectorUiState.Get.MultipleEntryPrimaryScreen,
    columnState: ScalingLazyColumnState,
    flowEngine: FlowEngine,
) {
    val selectEntry = flowEngine.getEntrySelector()
    Row {
        Spacer(Modifier.weight(0.052f)) // 5.2% side margin
        ScalingLazyColumn(
            columnState = columnState,
            modifier = Modifier.weight(0.896f).fillMaxSize(),
        ) {
            // flatten all credentials into one
            val credentials = credentialSelectorUiState.sortedEntries
            item {
                var title = stringResource(R.string.choose_sign_in_title)
                if (credentials.isNotEmpty()) {
                    if (credentials.all { it.credentialType == CredentialType.PASSKEY }) {
                        title = stringResource(R.string.choose_passkey_title)
                    } else if (credentials.all { it.credentialType == CredentialType.PASSWORD }) {
                        title = stringResource(R.string.choose_password_title)
                    }
                }

                SignInHeader(
                    icon = credentialSelectorUiState.icon,
                    title = title,
                )
            }

            credentials.forEach { credential: CredentialEntryInfo ->
                item {
                    CredentialsScreenChip(
                        primaryText =
                        {
                            WearButtonText(
                                text = credential.userName,
                                textAlign = TextAlign.Start,
                                maxLines = 2
                            )
                        },
                        onClick = { selectEntry(credential, false) },
                        secondaryText = {
                            WearSecondaryLabel(
                                text = credential.credentialTypeDisplayName.ifEmpty {
                                    credential.providerDisplayName
                                },
                                color = WearMaterialTheme.colors.onSurfaceVariant,
                                maxLines = 1 // See b/359649621 for context
                            )
                        },
                        icon = credential.icon,
                    )
                    CredentialsScreenChipSpacer()
                }
            }

            credentialSelectorUiState.authenticationEntryList.forEach { authenticationEntryInfo ->
                item {
                    LockedProviderChip(authenticationEntryInfo) {
                        selectEntry(authenticationEntryInfo, false)
                    }
                    CredentialsScreenChipSpacer()
                }
            }
            item {
                Spacer(modifier = Modifier.size(8.dp))
            }

            item {
                SignInOptionsChip { flowEngine.openSecondaryScreen() }
            }
            item {
                DismissChip { flowEngine.cancel() }
                BottomSpacer()
            }
        }
            Spacer(Modifier.weight(0.052f)) // 5.2% side margin
        }
}
