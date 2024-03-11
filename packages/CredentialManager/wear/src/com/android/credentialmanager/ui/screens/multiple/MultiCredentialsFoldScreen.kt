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

import com.android.credentialmanager.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

/**
 * Screen that shows multiple credentials to select from.
 *
 * @param credentialSelectorUiState The app bar view model.
 * @param columnState ScalingLazyColumn configuration to be be applied
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun MultiCredentialsFoldScreen(
    credentialSelectorUiState: CredentialSelectorUiState.Get.SingleEntryPerAccount,
    columnState: ScalingLazyColumnState,
    flowEngine: FlowEngine,
) {
    val selectEntry = flowEngine.getEntrySelector()
    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize(),
    ) {
        // flatten all credentials into one
        val credentials = credentialSelectorUiState.sortedEntries
        item {
            var title = stringResource(R.string.choose_sign_in_title)
            if (credentials.all{ it.credentialType == CredentialType.PASSKEY }) {
                title = stringResource(R.string.choose_passkey_title)
            } else if (credentials.all { it.credentialType == CredentialType.PASSWORD }) {
                title = stringResource(R.string.choose_password_title)
            }

            SignInHeader(
                icon = null,
                title = title,
                modifier = Modifier
                    .padding(top = 6.dp),
            )
        }

        credentials.forEach { credential: CredentialEntryInfo ->
                item {
                    CredentialsScreenChip(
                        label = credential.userName,
                        onClick = { selectEntry(credential, false) },
                        secondaryLabel = credential.credentialTypeDisplayName,
                        icon = credential.icon,
                    )
                }
            }

        credentialSelectorUiState.authenticationEntryList.forEach { authenticationEntryInfo ->
            item {
                LockedProviderChip(authenticationEntryInfo) {
                    selectEntry(authenticationEntryInfo, false) }
            }
        }
        item {
            SignInOptionsChip { flowEngine.openSecondaryScreen() }
        }
        item {
            DismissChip { flowEngine.cancel() }
        }
    }
}
