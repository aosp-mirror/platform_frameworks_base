@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.credentialmanager.createflow

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.CredentialSelectorViewModel
import com.android.credentialmanager.R
import com.android.credentialmanager.common.BaseEntry
import com.android.credentialmanager.common.CredentialType
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.common.ui.ActionButton
import com.android.credentialmanager.common.ui.ConfirmButton
import com.android.credentialmanager.common.ui.Entry
import com.android.credentialmanager.common.ui.ModalBottomSheet
import com.android.credentialmanager.common.ui.TextOnSurface
import com.android.credentialmanager.common.ui.TextSecondary
import com.android.credentialmanager.common.ui.TextOnSurfaceVariant
import com.android.credentialmanager.common.ui.ContainerCard
import com.android.credentialmanager.common.ui.ToggleVisibilityButton
import com.android.credentialmanager.ui.theme.LocalAndroidColorScheme

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
                        CreateScreenState.PASSKEY_INTRO -> ConfirmationCard(
                            onConfirm = viewModel::createFlowOnConfirmIntro,
                            onLearnMore = viewModel::createFlowOnLearnMore,
                        )
                        CreateScreenState.PROVIDER_SELECTION -> ProviderSelectionCard(
                            requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                            disabledProviderList = createCredentialUiState.disabledProviders,
                            sortedCreateOptionsPairs =
                            createCredentialUiState.sortedCreateOptionsPairs,
                            hasRemoteEntry = createCredentialUiState.remoteEntry != null,
                            onOptionSelected =
                            viewModel::createFlowOnEntrySelectedFromFirstUseScreen,
                            onDisabledProvidersSelected =
                            viewModel::createFlowOnDisabledProvidersSelected,
                            onMoreOptionsSelected =
                            viewModel::createFlowOnMoreOptionsSelectedOnProviderSelection,
                        )
                        CreateScreenState.CREATION_OPTION_SELECTION -> CreationSelectionCard(
                            requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                            enabledProviderList = createCredentialUiState.enabledProviders,
                            providerInfo = createCredentialUiState.activeEntry?.activeProvider!!,
                            hasDefaultProvider = createCredentialUiState.hasDefaultProvider,
                            createOptionInfo =
                            createCredentialUiState.activeEntry.activeEntryInfo
                                as CreateOptionInfo,
                            onOptionSelected = viewModel::createFlowOnEntrySelected,
                            onConfirm = viewModel::createFlowOnConfirmEntrySelected,
                            onMoreOptionsSelected =
                            viewModel::createFlowOnMoreOptionsSelectedOnCreationSelection,
                        )
                        CreateScreenState.MORE_OPTIONS_SELECTION -> MoreOptionsSelectionCard(
                            requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                            enabledProviderList = createCredentialUiState.enabledProviders,
                            disabledProviderList = createCredentialUiState.disabledProviders,
                            sortedCreateOptionsPairs =
                            createCredentialUiState.sortedCreateOptionsPairs,
                            hasDefaultProvider = createCredentialUiState.hasDefaultProvider,
                            isFromProviderSelection =
                            createCredentialUiState.isFromProviderSelection!!,
                            onBackProviderSelectionButtonSelected =
                            viewModel::createFlowOnBackProviderSelectionButtonSelected,
                            onBackCreationSelectionButtonSelected =
                            viewModel::createFlowOnBackCreationSelectionButtonSelected,
                            onOptionSelected =
                            viewModel::createFlowOnEntrySelectedFromMoreOptionScreen,
                            onDisabledProvidersSelected =
                            viewModel::createFlowOnDisabledProvidersSelected,
                            onRemoteEntrySelected = viewModel::createFlowOnEntrySelected,
                        )
                        CreateScreenState.MORE_OPTIONS_ROW_INTRO -> MoreOptionsRowIntroCard(
                            providerInfo = createCredentialUiState.activeEntry?.activeProvider!!,
                            onChangeDefaultSelected = viewModel::createFlowOnChangeDefaultSelected,
                            onUseOnceSelected = viewModel::createFlowOnUseOnceSelected,
                        )
                        CreateScreenState.EXTERNAL_ONLY_SELECTION -> ExternalOnlySelectionCard(
                            requestDisplayInfo = createCredentialUiState.requestDisplayInfo,
                            activeRemoteEntry =
                            createCredentialUiState.activeEntry?.activeEntryInfo!!,
                            onOptionSelected = viewModel::createFlowOnEntrySelected,
                            onConfirm = viewModel::createFlowOnConfirmEntrySelected,
                        )
                        CreateScreenState.MORE_ABOUT_PASSKEYS_INTRO ->
                            MoreAboutPasskeysIntroCard(
                                onBackPasskeyIntroButtonSelected =
                                viewModel::createFlowOnBackPasskeyIntroButtonSelected,
                            )
                    }
                }
                ProviderActivityState.READY_TO_LAUNCH -> {
                    // Launch only once per providerActivityState change so that the provider
                    // UI will not be accidentally launched twice.
                    LaunchedEffect(viewModel.uiState.providerActivityState) {
                        viewModel.launchProviderUi(providerActivityLauncher)
                    }
                }
                ProviderActivityState.PENDING -> {
                    // Hide our content when the provider activity is active.
                }
            }
        },
        onDismiss = viewModel::onUserCancel
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationCard(
    onConfirm: () -> Unit,
    onLearnMore: () -> Unit,
) {
    ContainerCard() {
        Column() {
            val onboardingImageResource = remember {
                mutableStateOf(R.drawable.ic_passkeys_onboarding)
            }
            if (isSystemInDarkTheme()) {
                onboardingImageResource.value = R.drawable.ic_passkeys_onboarding_dark
            } else {
                onboardingImageResource.value = R.drawable.ic_passkeys_onboarding
            }
            Image(
                painter = painterResource(onboardingImageResource.value),
                contentDescription = null,
                modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                    .padding(top = 24.dp, bottom = 12.dp).size(316.dp, 168.dp)
            )
            TextOnSurface(
                text = stringResource(R.string.passkey_creation_intro_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
            )
            Divider(
                thickness = 16.dp,
                color = Color.Transparent
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Image(
                    modifier = Modifier.size(24.dp),
                    painter = painterResource(R.drawable.ic_passkeys_onboarding_password),
                    contentDescription = null
                )
                TextSecondary(
                    text = stringResource(R.string.passkey_creation_intro_body_password),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                )
            }
            Divider(
                thickness = 16.dp,
                color = Color.Transparent
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Image(
                    modifier = Modifier.size(24.dp),
                    painter = painterResource(R.drawable.ic_passkeys_onboarding_fingerprint),
                    contentDescription = null
                )
                TextSecondary(
                    text = stringResource(R.string.passkey_creation_intro_body_fingerprint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                )
            }
            Divider(
                thickness = 16.dp,
                color = Color.Transparent
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Image(
                    modifier = Modifier.size(24.dp),
                    painter = painterResource(R.drawable.ic_passkeys_onboarding_device),
                    contentDescription = null
                )
                TextSecondary(
                    text = stringResource(R.string.passkey_creation_intro_body_device),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                )
            }
            Divider(
                thickness = 32.dp,
                color = Color.Transparent
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                ActionButton(
                    stringResource(R.string.string_learn_more),
                    onClick = onLearnMore
                )
                ConfirmButton(
                    stringResource(R.string.string_continue),
                    onClick = onConfirm
                )
            }
            Divider(
                thickness = 18.dp,
                color = Color.Transparent,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
    }
}

@Composable
fun ProviderSelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    disabledProviderList: List<DisabledProviderInfo>?,
    sortedCreateOptionsPairs: List<Pair<CreateOptionInfo, EnabledProviderInfo>>,
    hasRemoteEntry: Boolean,
    onOptionSelected: (ActiveEntry) -> Unit,
    onDisabledProvidersSelected: () -> Unit,
    onMoreOptionsSelected: () -> Unit,
) {
    ContainerCard() {
        Column() {
            Icon(
                bitmap = requestDisplayInfo.typeIcon.toBitmap().asImageBitmap(),
                contentDescription = null,
                tint = LocalAndroidColorScheme.current.colorAccentPrimaryVariant,
                modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                    .padding(top = 24.dp, bottom = 16.dp).size(32.dp)
            )
            TextOnSurface(
                text = stringResource(
                    R.string.choose_provider_title,
                    when (requestDisplayInfo.type) {
                        CredentialType.PASSKEY ->
                            stringResource(R.string.passkeys)
                        CredentialType.PASSWORD ->
                            stringResource(R.string.passwords)
                        CredentialType.UNKNOWN -> stringResource(R.string.sign_in_info)
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
            )
            Divider(
                thickness = 16.dp,
                color = Color.Transparent
            )
            TextSecondary(
                text = stringResource(R.string.choose_provider_body),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            ContainerCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 24.dp,
                    bottom = if (hasRemoteEntry) 24.dp else 16.dp
                ).align(alignment = Alignment.CenterHorizontally),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    sortedCreateOptionsPairs.forEach { entry ->
                        item {
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
                    }
                    item {
                        MoreOptionsDisabledProvidersRow(
                            disabledProviders = disabledProviderList,
                            onDisabledProvidersSelected =
                            onDisabledProvidersSelected,
                        )
                    }
                }
            }
            if (hasRemoteEntry) {
                Divider(
                    thickness = 24.dp,
                    color = Color.Transparent
                )
                Row(
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                ) {
                    ActionButton(
                        stringResource(R.string.string_more_options),
                        onMoreOptionsSelected
                    )
                }
            }
            Divider(
                thickness = 24.dp,
                color = Color.Transparent,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsSelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    enabledProviderList: List<EnabledProviderInfo>,
    disabledProviderList: List<DisabledProviderInfo>?,
    sortedCreateOptionsPairs: List<Pair<CreateOptionInfo, EnabledProviderInfo>>,
    hasDefaultProvider: Boolean,
    isFromProviderSelection: Boolean,
    onBackProviderSelectionButtonSelected: () -> Unit,
    onBackCreationSelectionButtonSelected: () -> Unit,
    onOptionSelected: (ActiveEntry) -> Unit,
    onDisabledProvidersSelected: () -> Unit,
    onRemoteEntrySelected: (BaseEntry) -> Unit,
) {
    ContainerCard() {
        Column() {
            TopAppBar(
                title = {
                    TextOnSurface(
                        text =
                        stringResource(
                            R.string.save_credential_to_title,
                            when (requestDisplayInfo.type) {
                                CredentialType.PASSKEY ->
                                    stringResource(R.string.passkey)
                                CredentialType.PASSWORD ->
                                    stringResource(R.string.password)
                                CredentialType.UNKNOWN -> stringResource(R.string.sign_in_info)
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick =
                        if (isFromProviderSelection)
                            onBackProviderSelectionButtonSelected
                        else onBackCreationSelectionButtonSelected
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            stringResource(R.string.accessibility_back_arrow_button)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.padding(top = 12.dp)
            )
            Divider(
                thickness = 8.dp,
                color = Color.Transparent
            )
            ContainerCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally)
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Only in the flows with default provider(not first time use) we can show the
                    // createOptions here, or they will be shown on ProviderSelectionCard
                    if (hasDefaultProvider) {
                        sortedCreateOptionsPairs.forEach { entry ->
                            item {
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
                                    })
                            }
                        }
                        item {
                            MoreOptionsDisabledProvidersRow(
                                disabledProviders = disabledProviderList,
                                onDisabledProvidersSelected =
                                onDisabledProvidersSelected,
                            )
                        }
                    }
                    enabledProviderList.forEach {
                        if (it.remoteEntry != null) {
                            item {
                                RemoteEntryRow(
                                    remoteInfo = it.remoteEntry!!,
                                    onRemoteEntrySelected = onRemoteEntrySelected,
                                )
                            }
                            return@forEach
                        }
                    }
                }
            }
            Divider(
                thickness = 8.dp,
                color = Color.Transparent,
                modifier = Modifier.padding(bottom = 40.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsRowIntroCard(
    providerInfo: EnabledProviderInfo,
    onChangeDefaultSelected: () -> Unit,
    onUseOnceSelected: () -> Unit,
) {
    ContainerCard() {
        Column() {
            Icon(
                Icons.Outlined.NewReleases,
                contentDescription = null,
                modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                    .padding(all = 24.dp),
                tint = LocalAndroidColorScheme.current.colorAccentPrimaryVariant,
            )
            TextOnSurface(
                text = stringResource(
                    R.string.use_provider_for_all_title,
                    providerInfo.displayName
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
            )
            TextSecondary(
                text = stringResource(R.string.use_provider_for_all_description),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(all = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                ActionButton(
                    stringResource(R.string.use_once),
                    onClick = onUseOnceSelected
                )
                ConfirmButton(
                    stringResource(R.string.set_as_default),
                    onClick = onChangeDefaultSelected
                )
            }
            Divider(
                thickness = 18.dp,
                color = Color.Transparent,
                modifier = Modifier.padding(bottom = 40.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationSelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    enabledProviderList: List<EnabledProviderInfo>,
    providerInfo: EnabledProviderInfo,
    createOptionInfo: CreateOptionInfo,
    onOptionSelected: (BaseEntry) -> Unit,
    onConfirm: () -> Unit,
    onMoreOptionsSelected: () -> Unit,
    hasDefaultProvider: Boolean,
) {
    ContainerCard() {
        Column() {
            Divider(
                thickness = 24.dp,
                color = Color.Transparent
            )
            Icon(
                bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.align(alignment = Alignment.CenterHorizontally).size(32.dp)
            )
            TextSecondary(
                text = providerInfo.displayName,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 10.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
            )
            TextOnSurface(
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
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
            )
            ContainerCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .padding(all = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
            ) {
                PrimaryCreateOptionRow(
                    requestDisplayInfo = requestDisplayInfo,
                    entryInfo = createOptionInfo,
                    onOptionSelected = onOptionSelected
                )
            }
            var createOptionsSize = 0
            var remoteEntry: RemoteInfo? = null
            enabledProviderList.forEach { enabledProvider ->
                if (enabledProvider.remoteEntry != null) {
                    remoteEntry = enabledProvider.remoteEntry
                }
                createOptionsSize += enabledProvider.createOptions.size
            }
            val shouldShowMoreOptionsButton = if (!hasDefaultProvider) {
                // User has already been presented with all options on the default provider
                // selection screen. Don't show them again. Therefore, only show the more option
                // button if remote option is present.
                remoteEntry != null
            } else {
                createOptionsSize > 1 || remoteEntry != null
            }
            Row(
                horizontalArrangement =
                if (shouldShowMoreOptionsButton) Arrangement.SpaceBetween else Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                if (shouldShowMoreOptionsButton) {
                    ActionButton(
                        stringResource(R.string.string_more_options),
                        onClick = onMoreOptionsSelected
                    )
                }
                ConfirmButton(
                    stringResource(R.string.string_continue),
                    onClick = onConfirm
                )
            }
            if (createOptionInfo.footerDescription != null) {
                Divider(
                    thickness = 1.dp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp)
                )
                TextSecondary(
                    text = createOptionInfo.footerDescription,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(
                        start = 29.dp, top = 8.dp, bottom = 18.dp, end = 28.dp
                    )
                )
            }
            Divider(
                thickness = 18.dp,
                color = Color.Transparent,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalOnlySelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    activeRemoteEntry: BaseEntry,
    onOptionSelected: (BaseEntry) -> Unit,
    onConfirm: () -> Unit,
) {
    ContainerCard() {
        Column() {
            Icon(
                painter = painterResource(R.drawable.ic_other_devices),
                contentDescription = null,
                tint = LocalAndroidColorScheme.current.colorAccentPrimaryVariant,
                modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                    .padding(all = 24.dp).size(32.dp)
            )
            TextOnSurface(
                text = stringResource(R.string.create_passkey_in_other_device_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
            )
            Divider(
                thickness = 24.dp,
                color = Color.Transparent
            )
            ContainerCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
            ) {
                PrimaryCreateOptionRow(
                    requestDisplayInfo = requestDisplayInfo,
                    entryInfo = activeRemoteEntry,
                    onOptionSelected = onOptionSelected
                )
            }
            Divider(
                thickness = 24.dp,
                color = Color.Transparent
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                ConfirmButton(
                    stringResource(R.string.string_continue),
                    onClick = onConfirm
                )
            }
            Divider(
                thickness = 18.dp,
                color = Color.Transparent,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreAboutPasskeysIntroCard(
    onBackPasskeyIntroButtonSelected: () -> Unit,
) {
    ContainerCard() {
        Column() {
            TopAppBar(
                title = {
                    TextOnSurface(
                        text =
                        stringResource(
                            R.string.more_about_passkeys_title
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackPasskeyIntroButtonSelected
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            stringResource(R.string.accessibility_back_arrow_button)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.padding(top = 12.dp)
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 68.dp)
            ) {
                TextOnSurfaceVariant(
                    text = stringResource(R.string.passwordless_technology_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextSecondary(
                    text = stringResource(R.string.passwordless_technology_detail),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Divider(
                    thickness = 24.dp,
                    color = Color.Transparent
                )
                TextOnSurfaceVariant(
                    text = stringResource(R.string.public_key_cryptography_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextSecondary(
                    text = stringResource(R.string.public_key_cryptography_detail),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Divider(
                    thickness = 24.dp,
                    color = Color.Transparent
                )
                TextOnSurfaceVariant(
                    text = stringResource(R.string.improved_account_security_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextSecondary(
                    text = stringResource(R.string.improved_account_security_detail),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Divider(
                    thickness = 24.dp,
                    color = Color.Transparent
                )
                TextOnSurfaceVariant(
                    text = stringResource(R.string.seamless_transition_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextSecondary(
                    text = stringResource(R.string.seamless_transition_detail),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Divider(
                thickness = 18.dp,
                color = Color.Transparent,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrimaryCreateOptionRow(
    requestDisplayInfo: RequestDisplayInfo,
    entryInfo: BaseEntry,
    onOptionSelected: (BaseEntry) -> Unit
) {
    Entry(
        onClick = { onOptionSelected(entryInfo) },
        icon = {
            if (entryInfo is CreateOptionInfo && entryInfo.profileIcon != null) {
                Image(
                    bitmap = entryInfo.profileIcon.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.padding(start = 10.dp).size(32.dp),
                )
            } else {
                Icon(
                    bitmap = requestDisplayInfo.typeIcon.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    tint = LocalAndroidColorScheme.current.colorAccentPrimaryVariant,
                    modifier = Modifier.padding(start = 10.dp).size(32.dp),
                )
            }
        },
        label = {
            Column() {
                when (requestDisplayInfo.type) {
                    CredentialType.PASSKEY -> {
                        TextOnSurfaceVariant(
                            text = requestDisplayInfo.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp, start = 5.dp),
                        )
                        TextSecondary(
                            text = if (requestDisplayInfo.subtitle != null) {
                                requestDisplayInfo.subtitle + " • " + stringResource(
                                    R.string.passkey_before_subtitle
                                )
                            } else {
                                stringResource(R.string.passkey_before_subtitle)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp, start = 5.dp),
                        )
                    }
                    CredentialType.PASSWORD -> {
                        TextOnSurfaceVariant(
                            text = requestDisplayInfo.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp, start = 5.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(
                                top = 4.dp, bottom = 16.dp,
                                start = 5.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val visualTransformation = remember { PasswordVisualTransformation() }
                            // This subtitle would never be null for create password
                            val originalPassword by remember {
                                mutableStateOf(requestDisplayInfo.subtitle ?: "")
                            }
                            val displayedPassword = remember {
                                mutableStateOf(
                                    visualTransformation.filter(
                                        AnnotatedString(originalPassword)
                                    ).text.text
                                )
                            }
                            TextSecondary(
                                text = displayedPassword.value,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            )

                            ToggleVisibilityButton(modifier = Modifier.padding(start = 4.dp)
                                .height(24.dp).width(24.dp), onToggle = {
                                if (it) {
                                    displayedPassword.value = originalPassword
                                } else {
                                    displayedPassword.value = visualTransformation.filter(
                                        AnnotatedString(originalPassword)
                                    ).text.text
                                }
                            })
                        }
                    }
                    CredentialType.UNKNOWN -> {
                        if (requestDisplayInfo.subtitle != null) {
                            TextOnSurfaceVariant(
                                text = requestDisplayInfo.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(top = 16.dp, start = 5.dp),
                            )
                            TextOnSurfaceVariant(
                                text = requestDisplayInfo.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 16.dp, start = 5.dp),
                            )
                        } else {
                            TextOnSurfaceVariant(
                                text = requestDisplayInfo.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(
                                    top = 16.dp, bottom = 16.dp, start = 5.dp
                                ),
                            )
                        }
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsInfoRow(
    requestDisplayInfo: RequestDisplayInfo,
    providerInfo: EnabledProviderInfo,
    createOptionInfo: CreateOptionInfo,
    onOptionSelected: () -> Unit
) {
    Entry(
        onClick = onOptionSelected,
        icon = {
            Image(
                modifier = Modifier.padding(start = 10.dp).size(32.dp),
                bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
                contentDescription = null
            )
        },
        label = {
            Column() {
                TextOnSurfaceVariant(
                    text = providerInfo.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp, start = 5.dp),
                )
                if (createOptionInfo.userProviderDisplayName != null) {
                    TextSecondary(
                        text = createOptionInfo.userProviderDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
                if (requestDisplayInfo.type == CredentialType.PASSKEY ||
                    requestDisplayInfo.type == CredentialType.PASSWORD
                ) {
                    if (createOptionInfo.passwordCount != null &&
                        createOptionInfo.passkeyCount != null
                    ) {
                        TextSecondary(
                            text =
                            stringResource(
                                R.string.more_options_usage_passwords_passkeys,
                                createOptionInfo.passwordCount,
                                createOptionInfo.passkeyCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp, start = 5.dp),
                        )
                    } else if (createOptionInfo.passwordCount != null) {
                        TextSecondary(
                            text =
                            stringResource(
                                R.string.more_options_usage_passwords,
                                createOptionInfo.passwordCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp, start = 5.dp),
                        )
                    } else if (createOptionInfo.passkeyCount != null) {
                        TextSecondary(
                            text =
                            stringResource(
                                R.string.more_options_usage_passkeys,
                                createOptionInfo.passkeyCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp, start = 5.dp),
                        )
                    } else {
                        Divider(
                            thickness = 16.dp,
                            color = Color.Transparent,
                        )
                    }
                } else {
                    if (createOptionInfo.totalCredentialCount != null) {
                        TextSecondary(
                            text =
                            stringResource(
                                R.string.more_options_usage_credentials,
                                createOptionInfo.totalCredentialCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp, start = 5.dp),
                        )
                    } else {
                        Divider(
                            thickness = 16.dp,
                            color = Color.Transparent,
                        )
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsDisabledProvidersRow(
    disabledProviders: List<ProviderInfo>?,
    onDisabledProvidersSelected: () -> Unit,
) {
    if (disabledProviders != null && disabledProviders.isNotEmpty()) {
        Entry(
            onClick = onDisabledProvidersSelected,
            icon = {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 16.dp),
                    tint = LocalAndroidColorScheme.current.colorAccentPrimaryVariant,
                )
            },
            label = {
                Column() {
                    TextOnSurfaceVariant(
                        text = stringResource(R.string.other_password_manager),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 16.dp, start = 5.dp),
                    )
                    // TODO: Update the subtitle once design is confirmed
                    TextSecondary(
                        text = disabledProviders.joinToString(separator = " • ") {
                            it.displayName
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp, start = 5.dp),
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteEntryRow(
    remoteInfo: RemoteInfo,
    onRemoteEntrySelected: (RemoteInfo) -> Unit,
) {
    Entry(
        onClick = { onRemoteEntrySelected(remoteInfo) },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_other_devices),
                contentDescription = null,
                tint = LocalAndroidColorScheme.current.colorAccentPrimaryVariant,
                modifier = Modifier.padding(start = 10.dp)
            )
        },
        label = {
            Column() {
                TextOnSurfaceVariant(
                    text = stringResource(R.string.another_device),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 18.dp)
                        .align(alignment = Alignment.CenterHorizontally),
                )
            }
        }
    )
}