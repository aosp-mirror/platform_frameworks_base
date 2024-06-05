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

import com.android.credentialmanager.ui.components.CredentialsScreenChip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.credentialmanager.CredentialSelectorUiState.Get.MultipleEntry
import com.android.credentialmanager.FlowEngine
import com.android.credentialmanager.R
import com.android.credentialmanager.common.ui.components.WearButtonText
import com.android.credentialmanager.common.ui.components.WearSecondaryLabel
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.ui.components.CredentialsScreenChipSpacer
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScalingLazyColumnState

/**
 * Screen that shows multiple credentials to select from, grouped by accounts
 *
 * @param credentialSelectorUiState The app bar view model.
 * @param columnState ScalingLazyColumn configuration to be be applied
 * @param modifier styling for composable
 * @param flowEngine [FlowEngine] that updates ui state for this screen
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun MultiCredentialsFlattenScreen(
    credentialSelectorUiState: MultipleEntry,
    columnState: ScalingLazyColumnState,
    flowEngine: FlowEngine,
) {
    val selectEntry = flowEngine.getEntrySelector()
    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            // make this credential specific if all credentials are same
            WearButtonText(
                text = stringResource(R.string.sign_in_options_title),
                textAlign = TextAlign.Start,
            )
        }

        credentialSelectorUiState.accounts.forEach { userNameEntries ->
            item {
                WearSecondaryLabel(
                    text = userNameEntries.userName,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }

            userNameEntries.sortedCredentialEntryList.forEach { credential: CredentialEntryInfo ->
                item {
                    CredentialsScreenChip(
                        label = credential.userName,
                        onClick = { selectEntry(credential, false) },
                        secondaryLabel = credential.credentialTypeDisplayName,
                        icon = credential.icon,
                        textAlign = TextAlign.Start
                    )

                    CredentialsScreenChipSpacer()
                }
            }
        }
        item {
            WearSecondaryLabel(
                text = stringResource(R.string.provider_list_title),
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
        }
        credentialSelectorUiState.actionEntryList.forEach { actionEntry ->
            item {
                    CredentialsScreenChip(
                        label = actionEntry.title,
                        onClick = { selectEntry(actionEntry, false) },
                        secondaryLabel = null,
                        icon = actionEntry.icon,
                    )
                    CredentialsScreenChipSpacer()
            }
        }
    }
}
