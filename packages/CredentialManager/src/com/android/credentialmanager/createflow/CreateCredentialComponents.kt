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

import android.text.TextUtils
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Divider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.CredentialSelectorViewModel
import com.android.credentialmanager.R
import com.android.credentialmanager.common.BaseEntry
import com.android.credentialmanager.common.CredentialType
import com.android.credentialmanager.common.ProviderActivityState
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
import com.android.credentialmanager.common.ui.MoreAboutPasskeySectionHeader
import com.android.credentialmanager.common.ui.MoreOptionTopAppBar
import com.android.credentialmanager.common.ui.SheetContainerCard
import com.android.credentialmanager.common.ui.PasskeyBenefitRow
import com.android.credentialmanager.common.ui.HeadlineText
import com.android.credentialmanager.logging.CreateCredentialEvent
import com.android.credentialmanager.ui.theme.LocalAndroidColorScheme
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
                        CreateScreenState.PASSKEY_INTRO -> PasskeyIntroCard(
                                onConfirm = viewModel::createFlowOnConfirmIntro,
                                onLearnMore = viewModel::createFlowOnLearnMore,
                                onLog = { viewModel.logUiEvent(it) },
                        )
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
                        CreateScreenState.MORE_ABOUT_PASSKEYS_INTRO -> MoreAboutPasskeysIntroCard(
                                onBackPasskeyIntroButtonSelected =
                                viewModel::createFlowOnBackPasskeyIntroButtonSelected,
                                onLog = { viewModel.logUiEvent(it) },
                        )
                    }
                }
                ProviderActivityState.READY_TO_LAUNCH -> {
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
                    // Hide our content when the provider activity is active.
                    viewModel.uiMetrics.log(
                            CreateCredentialEvent.CREDMAN_CREATE_CRED_PROVIDER_ACTIVITY_PENDING)
                }
            }
        },
        onDismiss = viewModel::onUserCancel,
        isInitialRender = viewModel.uiState.isInitialRender,
        onInitialRenderComplete = viewModel::onInitialRenderComplete,
    )
}

@Composable
fun PasskeyIntroCard(
    onConfirm: () -> Unit,
    onLearnMore: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    SheetContainerCard {
        item {
            val onboardingImageResource = remember {
                mutableStateOf(R.drawable.ic_passkeys_onboarding)
            }
            if (isSystemInDarkTheme()) {
                onboardingImageResource.value = R.drawable.ic_passkeys_onboarding_dark
            } else {
                onboardingImageResource.value = R.drawable.ic_passkeys_onboarding
            }
            Row(
                modifier = Modifier.wrapContentHeight().fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(onboardingImageResource.value),
                    contentDescription = null,
                    modifier = Modifier.size(316.dp, 168.dp)
                )
            }
        }
        item { Divider(thickness = 16.dp, color = Color.Transparent) }
        item { HeadlineText(text = stringResource(R.string.passkey_creation_intro_title)) }
        item { Divider(thickness = 16.dp, color = Color.Transparent) }
        item {
            PasskeyBenefitRow(
                leadingIconPainter = painterResource(R.drawable.ic_passkeys_onboarding_password),
                text = stringResource(R.string.passkey_creation_intro_body_password),
            )
        }
        item { Divider(thickness = 16.dp, color = Color.Transparent) }
        item {
            PasskeyBenefitRow(
                leadingIconPainter = painterResource(R.drawable.ic_passkeys_onboarding_fingerprint),
                text = stringResource(R.string.passkey_creation_intro_body_fingerprint),
            )
        }
        item { Divider(thickness = 16.dp, color = Color.Transparent) }
        item {
            PasskeyBenefitRow(
                leadingIconPainter = painterResource(R.drawable.ic_passkeys_onboarding_device),
                text = stringResource(R.string.passkey_creation_intro_body_device),
            )
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }

        item {
            CtaButtonRow(
                leftButton = {
                    ActionButton(
                        stringResource(R.string.string_learn_more),
                        onClick = onLearnMore
                    )
                },
                rightButton = {
                    ConfirmButton(
                        stringResource(R.string.string_continue),
                        onClick = onConfirm
                    )
                },
            )
        }
    }
    onLog(CreateCredentialEvent.CREDMAN_CREATE_CRED_PASSKEY_INTRO)
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
        onRemoteEntrySelected: (BaseEntry) -> Unit,
        onLog: @Composable (UiEventEnum) -> Unit,
) {
    SheetContainerCard(topAppBar = {
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
        )
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
        onOptionSelected: (BaseEntry) -> Unit,
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
                text = when (requestDisplayInfo.type) {
                    CredentialType.PASSKEY -> stringResource(
                        R.string.choose_create_option_passkey_title,
                        requestDisplayInfo.appName
                    )
                    CredentialType.PASSWORD -> stringResource(
                        R.string.choose_create_option_password_title,
                        requestDisplayInfo.appName
                    )
                    CredentialType.UNKNOWN -> stringResource(
                        R.string.choose_create_option_sign_in_title,
                        requestDisplayInfo.appName
                    )
                }
            )
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
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
        val footerDescription = createOptionInfo.footerDescription
        if (footerDescription != null && footerDescription.length > 0) {
            item {
                Divider(
                    thickness = 1.dp,
                    color = LocalAndroidColorScheme.current.colorOutlineVariant,
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
    onLog(CreateCredentialEvent.CREDMAN_CREATE_CRED_CREATION_OPTION_SELECTION)
}

@Composable
fun ExternalOnlySelectionCard(
        requestDisplayInfo: RequestDisplayInfo,
        activeRemoteEntry: BaseEntry,
        onOptionSelected: (BaseEntry) -> Unit,
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
fun MoreAboutPasskeysIntroCard(
    onBackPasskeyIntroButtonSelected: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    SheetContainerCard(
        topAppBar = {
            MoreOptionTopAppBar(
                text = stringResource(R.string.more_about_passkeys_title),
                onNavigationIconClicked = onBackPasskeyIntroButtonSelected,
                bottomPadding = 0.dp,
            )
        },
    ) {
        item {
            MoreAboutPasskeySectionHeader(
                text = stringResource(R.string.passwordless_technology_title)
            )
            Row(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                BodyMediumText(text = stringResource(R.string.passwordless_technology_detail))
            }
        }
        item {
            Divider(thickness = 8.dp, color = Color.Transparent)
            MoreAboutPasskeySectionHeader(
                text = stringResource(R.string.public_key_cryptography_title)
            )
            Row(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                BodyMediumText(text = stringResource(R.string.public_key_cryptography_detail))
            }
        }
        item {
            Divider(thickness = 8.dp, color = Color.Transparent)
            MoreAboutPasskeySectionHeader(
                text = stringResource(R.string.improved_account_security_title)
            )
            Row(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                BodyMediumText(text = stringResource(R.string.improved_account_security_detail))
            }
        }
        item {
            Divider(thickness = 8.dp, color = Color.Transparent)
            MoreAboutPasskeySectionHeader(
                text = stringResource(R.string.seamless_transition_title)
            )
            Row(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                BodyMediumText(text = stringResource(R.string.seamless_transition_detail))
            }
        }
    }
    onLog(CreateCredentialEvent.CREDMAN_CREATE_CRED_MORE_ABOUT_PASSKEYS_INTRO)
}

@Composable
fun PrimaryCreateOptionRow(
    requestDisplayInfo: RequestDisplayInfo,
    entryInfo: BaseEntry,
    onOptionSelected: (BaseEntry) -> Unit
) {
    Entry(
        onClick = { onOptionSelected(entryInfo) },
        iconImageBitmap =
        if (entryInfo is CreateOptionInfo && entryInfo.profileIcon != null) {
            entryInfo.profileIcon.toBitmap().asImageBitmap()
        } else {
            requestDisplayInfo.typeIcon.toBitmap().asImageBitmap()
        },
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
            if (createOptionInfo.passwordCount != null &&
                createOptionInfo.passkeyCount != null
            ) {
                stringResource(
                    R.string.more_options_usage_passwords_passkeys,
                    createOptionInfo.passwordCount,
                    createOptionInfo.passkeyCount
                )
            } else if (createOptionInfo.passwordCount != null) {
                stringResource(
                    R.string.more_options_usage_passwords,
                    createOptionInfo.passwordCount
                )
            } else if (createOptionInfo.passkeyCount != null) {
                stringResource(
                    R.string.more_options_usage_passkeys,
                    createOptionInfo.passkeyCount
                )
            } else {
                null
            }
        } else {
            if (createOptionInfo.totalCredentialCount != null) {
                stringResource(
                    R.string.more_options_usage_credentials,
                    createOptionInfo.totalCredentialCount
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