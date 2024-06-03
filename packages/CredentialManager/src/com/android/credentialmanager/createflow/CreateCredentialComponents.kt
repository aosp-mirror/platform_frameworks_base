/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.credentialmanager.createflow

import android.credentials.flags.Flags.selectorUiImprovementsEnabled
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.text.TextUtils
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Divider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.credentialmanager.CredentialSelectorViewModel
import com.android.credentialmanager.R
import com.android.credentialmanager.common.BiometricError
import com.android.credentialmanager.common.BiometricFlowType
import com.android.credentialmanager.common.BiometricPromptState
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.common.material.ModalBottomSheetDefaults
import com.android.credentialmanager.common.runBiometricFlowForCreate
import com.android.credentialmanager.common.ui.ActionButton
import com.android.credentialmanager.common.ui.BodyMediumText
import com.android.credentialmanager.common.ui.BodySmallText
import com.android.credentialmanager.common.ui.ConfirmButton
import com.android.credentialmanager.common.ui.CredentialContainerCard
import com.android.credentialmanager.common.ui.CtaButtonRow
import com.android.credentialmanager.common.ui.Entry
import com.android.credentialmanager.common.ui.HeadlineIcon
import com.android.credentialmanager.common.ui.LargeLabelTextOnSurfaceVariant
import com.android.credentialmanager.common.ui.ModalBottomSheet
import com.android.credentialmanager.common.ui.MoreOptionTopAppBar
import com.android.credentialmanager.common.ui.SheetContainerCard
import com.android.credentialmanager.common.ui.HeadlineText
import com.android.credentialmanager.logging.CreateCredentialEvent
import com.android.credentialmanager.model.creation.CreateOptionInfo
import com.android.credentialmanager.model.creation.RemoteInfo
import com.android.internal.logging.UiEventLogger.UiEventEnum

@Composable
fun CreateCredentialScreen(
    viewModel: CredentialSelectorViewModel,
    createCredentialUiState: CreateCredentialUiState,
    providerActivityLauncher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
) {
    ModalBottomSheet(
        sheetContent = {
            // Hide the sheet content as opposed to the whole bottom sheet to maintain the scrim
            // background color even when the content should be hidden while waiting for
            // results from the provider app.
            when (viewModel.uiState.providerActivityState) {
                ProviderActivityState.NOT_APPLICABLE -> {
                    when (createCredentialUiState.currentScreenState) {
                        CreateScreenState.CREATION_OPTION_SELECTION -> CreationSelectionCard(
                                requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                                enabledProviderList = createCredentialUiState.enabledProviders,
                                providerInfo = createCredentialUiState
                                        .activeEntry?.activeProvider!!,
                                createOptionInfo =
                                createCredentialUiState.activeEntry.activeEntryInfo
                                        as CreateOptionInfo,
                                onOptionSelected = viewModel::createFlowOnEntrySelected,
                                onConfirm = viewModel::createFlowOnConfirmEntrySelected,
                                onMoreOptionsSelected =
                                viewModel::createFlowOnMoreOptionsSelectedOnCreationSelection,
                                onLog = { viewModel.logUiEvent(it) },
                        )
                        CreateScreenState.BIOMETRIC_SELECTION ->
                            BiometricSelectionPage(
                                biometricEntry = createCredentialUiState
                                    .activeEntry?.activeEntryInfo,
                                onCancelFlowAndFinish = viewModel::onUserCancel,
                                onIllegalScreenStateAndFinish = viewModel::onIllegalUiState,
                                onMoreOptionSelected =
                                viewModel::createFlowOnMoreOptionsOnlySelectedOnCreationSelection,
                                requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                                enabledProviderInfo = createCredentialUiState
                                        .activeEntry?.activeProvider!!,
                                onBiometricEntrySelected =
                                viewModel::createFlowOnEntrySelected,
                                fallbackToOriginalFlow =
                                viewModel::fallbackFromBiometricToNormalFlow,
                                getBiometricPromptState =
                                viewModel::getBiometricPromptStateStatus,
                                onBiometricPromptStateChange =
                                viewModel::onBiometricPromptStateChange,
                                getBiometricCancellationSignal =
                                viewModel::getBiometricCancellationSignal,
                                onLog = { viewModel.logUiEvent(it) },
                            )
                        CreateScreenState.MORE_OPTIONS_SELECTION_ONLY -> MoreOptionsSelectionCard(
                                requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                                enabledProviderList = createCredentialUiState.enabledProviders,
                                disabledProviderList = createCredentialUiState.disabledProviders,
                                sortedCreateOptionsPairs =
                                createCredentialUiState.sortedCreateOptionsPairs,
                                onBackCreationSelectionButtonSelected =
                                viewModel::createFlowOnBackCreationSelectionButtonSelected,
                                onOptionSelected =
                                viewModel::createFlowOnEntrySelectedFromMoreOptionScreen,
                                onDisabledProvidersSelected =
                                viewModel::createFlowOnLaunchSettings,
                                onRemoteEntrySelected = viewModel::createFlowOnEntrySelected,
                                onLog = { viewModel.logUiEvent(it) },
                                customTopAppBar = { MoreOptionTopAppBar(
                                        text = stringResource(
                                                R.string.save_credential_to_title,
                                                when (createCredentialUiState.requestDisplayInfo
                                                        .type) {
                                                    CredentialType.PASSKEY ->
                                                        stringResource(R.string.passkey)
                                                    CredentialType.PASSWORD ->
                                                        stringResource(R.string.password)
                                                    CredentialType.UNKNOWN -> stringResource(
                                                            R.string.sign_in_info)
                                                }
                                        ),
                                        onNavigationIconClicked = viewModel::onUserCancel,
                                        bottomPadding = 16.dp,
                                        navigationIcon = Icons.Filled.Close,
                                        navigationIconContentDescription = stringResource(
                                                R.string.accessibility_close_button
                                        )
                                )}
                        )
                        CreateScreenState.MORE_OPTIONS_SELECTION -> MoreOptionsSelectionCard(
                                requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                                enabledProviderList = createCredentialUiState.enabledProviders,
                                disabledProviderList = createCredentialUiState.disabledProviders,
                                sortedCreateOptionsPairs =
                                createCredentialUiState.sortedCreateOptionsPairs,
                                onBackCreationSelectionButtonSelected =
                                viewModel::createFlowOnBackCreationSelectionButtonSelected,
                                onOptionSelected =
                                viewModel::createFlowOnEntrySelectedFromMoreOptionScreen,
                                onDisabledProvidersSelected =
                                viewModel::createFlowOnLaunchSettings,
                                onRemoteEntrySelected = viewModel::createFlowOnEntrySelected,
                                onLog = { viewModel.logUiEvent(it) },
                        )
                        CreateScreenState.DEFAULT_PROVIDER_CONFIRMATION -> {
                            if (createCredentialUiState.activeEntry == null) {
                                viewModel.onIllegalUiState("Expect active entry to be non-null" +
                                        " upon default provider dialog.")
                            } else {
                                NonDefaultUsageConfirmationCard(
                                        selectedEntry = createCredentialUiState.activeEntry,
                                        onIllegalScreenState = viewModel::onIllegalUiState,
                                        onLaunchSettings =
                                        viewModel::createFlowOnLaunchSettings,
                                        onUseOnceSelected = viewModel::createFlowOnUseOnceSelected,
                                        onLog = { viewModel.logUiEvent(it) },
                                )
                            }
                        }
                        CreateScreenState.EXTERNAL_ONLY_SELECTION -> ExternalOnlySelectionCard(
                                requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                                activeRemoteEntry =
                                createCredentialUiState.activeEntry?.activeEntryInfo!!,
                                onOptionSelected = viewModel::createFlowOnEntrySelected,
                                onConfirm = viewModel::createFlowOnConfirmEntrySelected,
                                onLog = { viewModel.logUiEvent(it) },
                        )
                    }
                }
                ProviderActivityState.READY_TO_LAUNCH -> {
                    // This is a native bug from ModalBottomSheet. For now, use the temporary
                    // solution of not having an empty state.
                    if (viewModel.uiState.isAutoSelectFlow) {
                        Divider(
                            thickness = Dp.Hairline, color = ModalBottomSheetDefaults.scrimColor
                        )
                    }
                    // Launch only once per providerActivityState change so that the provider
                    // UI will not be accidentally launched twice.
                    LaunchedEffect(viewModel.uiState.providerActivityState) {
                        viewModel.launchProviderUi(providerActivityLauncher)
                    }
                    viewModel.uiMetrics.log(
                            CreateCredentialEvent
                                    .CREDMAN_CREATE_CRED_PROVIDER_ACTIVITY_READY_TO_LAUNCH)
                }
                ProviderActivityState.PENDING -> {
                    if (viewModel.uiState.isAutoSelectFlow) {
                        Divider(
                            thickness = Dp.Hairline, color = ModalBottomSheetDefaults.scrimColor
                        )
                    }
                    // Hide our content when the provider activity is active.
                    viewModel.uiMetrics.log(
                            CreateCredentialEvent.CREDMAN_CREATE_CRED_PROVIDER_ACTIVITY_PENDING)
                }
            }
        },
        onDismiss = viewModel::onUserCancel,
        isInitialRender = viewModel.uiState.isInitialRender,
        isAutoSelectFlow = viewModel.uiState.isAutoSelectFlow,
        onInitialRenderComplete = viewModel::onInitialRenderComplete,
    )
}

@Composable
fun MoreOptionsSelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    enabledProviderList: List<EnabledProviderInfo>,
    disabledProviderList: List<DisabledProviderInfo>?,
    sortedCreateOptionsPairs: List<Pair<CreateOptionInfo, EnabledProviderInfo>>,
    onBackCreationSelectionButtonSelected: () -> Unit,
    onOptionSelected: (ActiveEntry) -> Unit,
    onDisabledProvidersSelected: () -> Unit,
    onRemoteEntrySelected: (EntryInfo) -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
    customTopAppBar: (@Composable() () -> Unit)? = null
) {
    SheetContainerCard(topAppBar = {
        if (customTopAppBar != null) {
            customTopAppBar()
        } else {
            MoreOptionTopAppBar(
                    text = stringResource(
                            R.string.save_credential_to_title,
                            when (requestDisplayInfo.type) {
                                CredentialType.PASSKEY ->
                                    stringResource(R.string.passkey)
                                CredentialType.PASSWORD ->
                                    stringResource(R.string.password)
                                CredentialType.UNKNOWN -> stringResource(R.string.sign_in_info)
                            }
                    ),
                    onNavigationIconClicked = onBackCreationSelectionButtonSelected,
                    bottomPadding = 16.dp,
                    navigationIcon = Icons.Filled.ArrowBack,
                    navigationIconContentDescription = stringResource(
                            R.string.accessibility_back_arrow_button
                    )
            )
        }
    }) {
        // bottom padding already
        item {
            CredentialContainerCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    sortedCreateOptionsPairs.forEach { entry ->
                        MoreOptionsInfoRow(
                            requestDisplayInfo = requestDisplayInfo,
                            providerInfo = entry.second,
                            createOptionInfo = entry.first,
                            onOptionSelected = {
                                onOptionSelected(
                                    ActiveEntry(
                                        entry.second,
                                        entry.first
                                    )
                                )
                            }
                        )
                    }
                    MoreOptionsDisabledProvidersRow(
                        disabledProviders = disabledProviderList,
                        onDisabledProvidersSelected =
                        onDisabledProvidersSelected,
                    )
                    enabledProviderList.forEach {
                        if (it.remoteEntry != null) {
                            RemoteEntryRow(
                                remoteInfo = it.remoteEntry!!,
                                onRemoteEntrySelected = onRemoteEntrySelected,
                            )
                            return@forEach
                        }
                    }
                }
            }
        }
    }
    onLog(CreateCredentialEvent.CREDMAN_CREATE_CRED_MORE_OPTIONS_SELECTION)
}

@Composable
fun NonDefaultUsageConfirmationCard(
        selectedEntry: ActiveEntry,
        onIllegalScreenState: (String) -> Unit,
        onLaunchSettings: () -> Unit,
        onUseOnceSelected: () -> Unit,
        onLog: @Composable (UiEventEnum) -> Unit,
) {
    val entryInfo = selectedEntry.activeEntryInfo
    if (entryInfo !is CreateOptionInfo) {
        onIllegalScreenState("Encountered unexpected type of entry during the default provider" +
            " dialog: ${entryInfo::class}")
        return
    }
    SheetContainerCard {
        item { HeadlineIcon(imageVector = Icons.Outlined.NewReleases) }
        item { Divider(thickness = 16.dp, color = Color.Transparent) }
        item {
            HeadlineText(
                text = stringResource(
                    R.string.use_provider_for_all_title, selectedEntry.activeProvider.displayName)
            )
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        item {
            Row(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                BodyMediumText(text = stringResource(
                    R.string.use_provider_for_all_description, entryInfo.userProviderDisplayName))
            }
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        item {
            CtaButtonRow(
                leftButton = {
                    ActionButton(
                        stringResource(R.string.settings),
                        onClick = onLaunchSettings,
                    )
                },
                rightButton = {
                    ConfirmButton(
                        stringResource(R.string.use_once),
                        onClick = onUseOnceSelected,
                    )
                },
            )
        }
    }
    onLog(CreateCredentialEvent.CREDMAN_CREATE_CRED_MORE_OPTIONS_ROW_INTRO)
}

@Composable
fun CreationSelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    enabledProviderList: List<EnabledProviderInfo>,
    providerInfo: EnabledProviderInfo,
    createOptionInfo: CreateOptionInfo,
    onOptionSelected: (EntryInfo) -> Unit,
    onConfirm: () -> Unit,
    onMoreOptionsSelected: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    SheetContainerCard {
        item {
            HeadlineIcon(
                bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
                tint = Color.Unspecified,
            )
        }
        item { Divider(thickness = 4.dp, color = Color.Transparent) }
        item { LargeLabelTextOnSurfaceVariant(text = providerInfo.displayName) }
        item { Divider(thickness = 16.dp, color = Color.Transparent) }
        item {
            HeadlineText(
                text = stringResource(
                    getCreateTitleResCode(requestDisplayInfo),
                    requestDisplayInfo.appName)
            )
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }

        val footerDescription = createOptionInfo.footerDescription
        if (selectorUiImprovementsEnabled()) {
            if (!footerDescription.isNullOrBlank()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        BodyMediumText(text = footerDescription)
                    }
                }
                item { Divider(thickness = 24.dp, color = Color.Transparent) }
            }
        }
        item {
            CredentialContainerCard {
                PrimaryCreateOptionRow(
                    requestDisplayInfo = requestDisplayInfo,
                    entryInfo = createOptionInfo,
                    onOptionSelected = onOptionSelected
                )
            }
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        var createOptionsSize = 0
        var remoteEntry: RemoteInfo? = null
        enabledProviderList.forEach { enabledProvider ->
            if (enabledProvider.remoteEntry != null) {
                remoteEntry = enabledProvider.remoteEntry
            }
            createOptionsSize += enabledProvider.sortedCreateOptions.size
        }
        val shouldShowMoreOptionsButton = createOptionsSize > 1 || remoteEntry != null
        item {
            CtaButtonRow(
                leftButton = if (shouldShowMoreOptionsButton) {
                    {
                        ActionButton(
                            stringResource(R.string.string_more_options),
                            onMoreOptionsSelected
                        )
                    }
                } else null,
                rightButton = {
                    ConfirmButton(
                        stringResource(R.string.string_continue),
                        onClick = onConfirm
                    )
                },
            )
        }
        if (!selectorUiImprovementsEnabled()) {
            if (footerDescription != null && footerDescription.length > 0) {
                item {
                    Divider(
                        thickness = 1.dp,
                        color = LocalAndroidColorScheme.current.outlineVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        BodySmallText(text = footerDescription)
                    }
                }
            }
        }
    }
    onLog(CreateCredentialEvent.CREDMAN_CREATE_CRED_CREATION_OPTION_SELECTION)
}

@Composable
fun ExternalOnlySelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    activeRemoteEntry: EntryInfo,
    onOptionSelected: (EntryInfo) -> Unit,
    onConfirm: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    SheetContainerCard {
        item { HeadlineIcon(imageVector = Icons.Outlined.QrCodeScanner) }
        item { Divider(thickness = 16.dp, color = Color.Transparent) }
        item {
            HeadlineText(
                text = stringResource(
                    when (requestDisplayInfo.type) {
                        CredentialType.PASSKEY -> R.string.create_passkey_in_other_device_title
                        CredentialType.PASSWORD -> R.string.save_password_on_other_device_title
                        else -> R.string.save_sign_in_on_other_device_title
                    }
                )
            )
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        item {
            CredentialContainerCard {
                PrimaryCreateOptionRow(
                    requestDisplayInfo = requestDisplayInfo,
                    entryInfo = activeRemoteEntry,
                    onOptionSelected = onOptionSelected
                )
            }
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        item {
            CtaButtonRow(
                rightButton = {
                    ConfirmButton(
                        stringResource(R.string.string_continue),
                        onClick = onConfirm
                    )
                },
            )
        }
    }
    onLog(CreateCredentialEvent.CREDMAN_CREATE_CRED_EXTERNAL_ONLY_SELECTION)
}

@Composable
fun PrimaryCreateOptionRow(
    requestDisplayInfo: RequestDisplayInfo,
    entryInfo: EntryInfo,
    onOptionSelected: (EntryInfo) -> Unit
) {
    Entry(
        onClick = { onOptionSelected(entryInfo) },
        iconImageBitmap = ((entryInfo as? CreateOptionInfo)?.profileIcon
            ?: requestDisplayInfo.typeIcon)
            .toBitmap().asImageBitmap(),
        shouldApplyIconImageBitmapTint = !(entryInfo is CreateOptionInfo &&
            entryInfo.profileIcon != null),
        entryHeadlineText = requestDisplayInfo.title,
        entrySecondLineText = when (requestDisplayInfo.type) {
            CredentialType.PASSKEY -> {
                if (!TextUtils.isEmpty(requestDisplayInfo.subtitle)) {
                    requestDisplayInfo.subtitle + " • " + stringResource(
                        R.string.passkey_before_subtitle
                    )
                } else {
                    stringResource(R.string.passkey_before_subtitle)
                }
            }
            // Set passwordValue instead
            CredentialType.PASSWORD -> null
            CredentialType.UNKNOWN -> requestDisplayInfo.subtitle
        },
        passwordValue =
        if (requestDisplayInfo.type == CredentialType.PASSWORD)
        // This subtitle would never be null for create password
            requestDisplayInfo.subtitle ?: ""
        else null,
        enforceOneLine = true,
    )
}

@Composable
fun MoreOptionsInfoRow(
    requestDisplayInfo: RequestDisplayInfo,
    providerInfo: EnabledProviderInfo,
    createOptionInfo: CreateOptionInfo,
    onOptionSelected: () -> Unit
) {
    Entry(
        onClick = onOptionSelected,
        iconImageBitmap = providerInfo.icon.toBitmap().asImageBitmap(),
        entryHeadlineText = providerInfo.displayName,
        entrySecondLineText = createOptionInfo.userProviderDisplayName,
        entryThirdLineText =
        if (requestDisplayInfo.type == CredentialType.PASSKEY ||
            requestDisplayInfo.type == CredentialType.PASSWORD) {
            val passwordCount = createOptionInfo.passwordCount
            val passkeyCount = createOptionInfo.passkeyCount
            if (passwordCount != null && passkeyCount != null) {
                stringResource(
                    R.string.more_options_usage_passwords_passkeys,
                    passwordCount,
                    passkeyCount
                )
            } else if (passwordCount != null) {
                stringResource(
                    R.string.more_options_usage_passwords,
                    passwordCount
                )
            } else if (passkeyCount != null) {
                stringResource(
                    R.string.more_options_usage_passkeys,
                    passkeyCount
                )
            } else {
                null
            }
        } else {
            val totalCredentialCount = createOptionInfo.totalCredentialCount
            if (totalCredentialCount != null) {
                stringResource(
                    R.string.more_options_usage_credentials,
                    totalCredentialCount
                )
            } else {
                null
            }
        },
    )
}

@Composable
fun MoreOptionsDisabledProvidersRow(
    disabledProviders: List<ProviderInfo>?,
    onDisabledProvidersSelected: () -> Unit,
) {
    if (disabledProviders != null && disabledProviders.isNotEmpty()) {
        Entry(
            onClick = onDisabledProvidersSelected,
            iconImageVector = Icons.Filled.Add,
            entryHeadlineText = stringResource(R.string.other_password_manager),
            entrySecondLineText = disabledProviders.joinToString(separator = " • ") {
                it.displayName
            },
        )
    }
}

@Composable
fun RemoteEntryRow(
    remoteInfo: RemoteInfo,
    onRemoteEntrySelected: (RemoteInfo) -> Unit,
) {
    Entry(
        onClick = { onRemoteEntrySelected(remoteInfo) },
        iconImageVector = Icons.Outlined.QrCodeScanner,
        entryHeadlineText = stringResource(R.string.another_device),
    )
}

@Composable
internal fun BiometricSelectionPage(
    biometricEntry: EntryInfo?,
    onMoreOptionSelected: () -> Unit,
    requestDisplayInfo: RequestDisplayInfo,
    enabledProviderInfo: EnabledProviderInfo,
    onBiometricEntrySelected: (
        EntryInfo,
        BiometricPrompt.AuthenticationResult?,
        BiometricError?
    ) -> Unit,
    onCancelFlowAndFinish: () -> Unit,
    onIllegalScreenStateAndFinish: (String) -> Unit,
    fallbackToOriginalFlow: (BiometricFlowType) -> Unit,
    getBiometricPromptState: () -> BiometricPromptState,
    onBiometricPromptStateChange: (BiometricPromptState) -> Unit,
    getBiometricCancellationSignal: () -> CancellationSignal,
    onLog: @Composable (UiEventEnum) -> Unit
) {
    if (biometricEntry == null) {
        fallbackToOriginalFlow(BiometricFlowType.CREATE)
        return
    }
    val biometricFlowCalled = runBiometricFlowForCreate(
        biometricEntry = biometricEntry,
        context = LocalContext.current,
        openMoreOptionsPage = onMoreOptionSelected,
        sendDataToProvider = onBiometricEntrySelected,
        onCancelFlowAndFinish = onCancelFlowAndFinish,
        getBiometricPromptState = getBiometricPromptState,
        onBiometricPromptStateChange = onBiometricPromptStateChange,
        createRequestDisplayInfo = requestDisplayInfo,
        createProviderInfo = enabledProviderInfo,
        onBiometricFailureFallback = fallbackToOriginalFlow,
        onIllegalStateAndFinish = onIllegalScreenStateAndFinish,
        getBiometricCancellationSignal = getBiometricCancellationSignal
    )
    if (biometricFlowCalled) {
        onLog(CreateCredentialEvent.CREDMAN_CREATE_CRED_BIOMETRIC_FLOW_LAUNCHED)
    }
}
