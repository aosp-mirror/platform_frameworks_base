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

package com.android.credentialmanager.getflow

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.compose.rememberSystemUiController
import com.android.credentialmanager.CredentialSelectorViewModel
import com.android.credentialmanager.R
import com.android.credentialmanager.common.BaseEntry
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.common.ui.ConfirmButton
import com.android.credentialmanager.common.ui.CredentialContainerCard
import com.android.credentialmanager.common.ui.CtaButtonRow
import com.android.credentialmanager.common.ui.HeadlineIcon
import com.android.credentialmanager.common.ui.HeadlineText
import com.android.credentialmanager.common.ui.LargeLabelTextOnSurfaceVariant
import com.android.credentialmanager.common.ui.ModalBottomSheet
import com.android.credentialmanager.common.ui.SheetContainerCard
import com.android.credentialmanager.common.ui.setBottomSheetSystemBarsColor
import com.android.credentialmanager.logging.GetCredentialEvent
import com.android.internal.logging.UiEventLogger


@Composable
fun GetGenericCredentialScreen(
        viewModel: CredentialSelectorViewModel,
        getCredentialUiState: GetCredentialUiState,
        providerActivityLauncher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
) {
    val sysUiController = rememberSystemUiController()
    setBottomSheetSystemBarsColor(sysUiController)
    ModalBottomSheet(
        sheetContent = {
            when (viewModel.uiState.providerActivityState) {
                ProviderActivityState.NOT_APPLICABLE -> {
                    PrimarySelectionCardGeneric(
                            requestDisplayInfo = getCredentialUiState.requestDisplayInfo,
                            providerDisplayInfo = getCredentialUiState.providerDisplayInfo,
                            providerInfoList = getCredentialUiState.providerInfoList,
                            onEntrySelected = viewModel::getFlowOnEntrySelected,
                            onConfirm = viewModel::getFlowOnConfirmEntrySelected,
                            onLog = { viewModel.logUiEvent(it) },
                    )
                    viewModel.uiMetrics.log(GetCredentialEvent
                            .CREDMAN_GET_CRED_SCREEN_PRIMARY_SELECTION)
                }
                ProviderActivityState.READY_TO_LAUNCH -> {
                    // Launch only once per providerActivityState change so that the provider
                    // UI will not be accidentally launched twice.
                    LaunchedEffect(viewModel.uiState.providerActivityState) {
                        viewModel.launchProviderUi(providerActivityLauncher)
                    }
                    viewModel.uiMetrics.log(GetCredentialEvent
                            .CREDMAN_GET_CRED_PROVIDER_ACTIVITY_READY_TO_LAUNCH)
                }
                ProviderActivityState.PENDING -> {
                    // Hide our content when the provider activity is active.
                    viewModel.uiMetrics.log(GetCredentialEvent
                            .CREDMAN_GET_CRED_PROVIDER_ACTIVITY_PENDING)
                }
            }
        },
        onDismiss = viewModel::onUserCancel,
    )
}

@Composable
fun PrimarySelectionCardGeneric(
        requestDisplayInfo: RequestDisplayInfo,
        providerDisplayInfo: ProviderDisplayInfo,
        providerInfoList: List<ProviderInfo>,
        onEntrySelected: (BaseEntry) -> Unit,
        onConfirm: () -> Unit,
        onLog: @Composable (UiEventLogger.UiEventEnum) -> Unit,
) {
    val sortedUserNameToCredentialEntryList =
            providerDisplayInfo.sortedUserNameToCredentialEntryList
    val totalEntriesCount = sortedUserNameToCredentialEntryList
            .flatMap { it.sortedCredentialEntryList }.size
    SheetContainerCard {
        // When only one provider (not counting the remote-only provider) exists, display that
        // provider's icon + name up top.
        if (providerInfoList.size <= 2) { // It's only possible to be the single provider case
            // if we are started with no more than 2 providers.
            val nonRemoteProviderList = providerInfoList.filter(
                { it.credentialEntryList.isNotEmpty() || it.authenticationEntryList.isNotEmpty() }
            )
            if (nonRemoteProviderList.size == 1) {
                val providerInfo = nonRemoteProviderList.firstOrNull() // First should always work
                // but just to be safe.
                if (providerInfo != null) {
                    item {
                        HeadlineIcon(
                                bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
                                tint = Color.Unspecified,
                        )
                    }
                    item { Divider(thickness = 4.dp, color = Color.Transparent) }
                    item { LargeLabelTextOnSurfaceVariant(text = providerInfo.displayName) }
                    item { Divider(thickness = 16.dp, color = Color.Transparent) }
                }
            }
        }

        item {
            HeadlineText(
                    text = stringResource(
                            if (totalEntriesCount == 1) {
                                R.string.get_dialog_title_use_info_on
                            } else {
                                R.string.get_dialog_title_choose_option_for
                            },
                            requestDisplayInfo.appName
                    ),
            )
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        item {
            CredentialContainerCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    sortedUserNameToCredentialEntryList.forEach {
                        // TODO(b/275375861): fallback UI merges entries by account names.
                        //  Need a strategy to be able to show all entries.
                        CredentialEntryRow(
                                credentialEntryInfo = it.sortedCredentialEntryList.first(),
                                onEntrySelected = onEntrySelected,
                                enforceOneLine = true,
                        )
                    }
                }
            }
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        item {
            if (totalEntriesCount == 1) {
                CtaButtonRow(
                    rightButton = {
                        ConfirmButton(
                            stringResource(R.string.get_dialog_button_label_continue),
                            onClick = onConfirm
                        )
                    }
                )
            }
        }
    }
    onLog(GetCredentialEvent.CREDMAN_GET_CRED_PRIMARY_SELECTION_CARD)
}
