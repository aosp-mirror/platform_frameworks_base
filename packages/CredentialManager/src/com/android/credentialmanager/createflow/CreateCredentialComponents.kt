@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.credentialmanager.createflow

import android.credentials.Credential.TYPE_PASSWORD_CREDENTIAL
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.R
import com.android.credentialmanager.common.material.ModalBottomSheetLayout
import com.android.credentialmanager.common.material.ModalBottomSheetValue
import com.android.credentialmanager.common.material.rememberModalBottomSheetState
import com.android.credentialmanager.common.ui.CancelButton
import com.android.credentialmanager.common.ui.ConfirmButton
import com.android.credentialmanager.common.ui.Entry
import com.android.credentialmanager.common.ui.TextOnSurface
import com.android.credentialmanager.common.ui.TextSecondary
import com.android.credentialmanager.common.ui.TextOnSurfaceVariant
import com.android.credentialmanager.common.ui.ContainerCard
import com.android.credentialmanager.ui.theme.EntryShape
import com.android.credentialmanager.ui.theme.LocalAndroidColorScheme
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential.Companion.TYPE_PUBLIC_KEY_CREDENTIAL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCredentialScreen(
    viewModel: CreateCredentialViewModel,
    providerActivityLauncher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
) {
    val state = rememberModalBottomSheetState(
        initialValue = ModalBottomSheetValue.Expanded,
        skipHalfExpanded = true
    )
    ModalBottomSheetLayout(
        sheetBackgroundColor = MaterialTheme.colorScheme.surface,
        sheetState = state,
        sheetContent = {
            val uiState = viewModel.uiState
            if (!uiState.hidden) {
                when (uiState.currentScreenState) {
                    CreateScreenState.PASSKEY_INTRO -> ConfirmationCard(
                        onConfirm = viewModel::onConfirmIntro,
                        onCancel = viewModel::onCancel,
                    )
                    CreateScreenState.PROVIDER_SELECTION -> ProviderSelectionCard(
                        requestDisplayInfo = uiState.requestDisplayInfo,
                        enabledProviderList = uiState.enabledProviders,
                        disabledProviderList = uiState.disabledProviders,
                        onCancel = viewModel::onCancel,
                        onOptionSelected = viewModel::onEntrySelectedFromFirstUseScreen,
                        onDisabledPasswordManagerSelected =
                        viewModel::onDisabledPasswordManagerSelected,
                        onRemoteEntrySelected = viewModel::onEntrySelected,
                    )
                    CreateScreenState.CREATION_OPTION_SELECTION -> CreationSelectionCard(
                        requestDisplayInfo = uiState.requestDisplayInfo,
                        enabledProviderList = uiState.enabledProviders,
                        providerInfo = uiState.activeEntry?.activeProvider!!,
                        createOptionInfo = uiState.activeEntry.activeEntryInfo as CreateOptionInfo,
                        showActiveEntryOnly = uiState.showActiveEntryOnly,
                        onOptionSelected = viewModel::onEntrySelected,
                        onConfirm = viewModel::onConfirmEntrySelected,
                        onCancel = viewModel::onCancel,
                        onMoreOptionsSelected = viewModel::onMoreOptionsSelected,
                    )
                    CreateScreenState.MORE_OPTIONS_SELECTION -> MoreOptionsSelectionCard(
                        requestDisplayInfo = uiState.requestDisplayInfo,
                        enabledProviderList = uiState.enabledProviders,
                        disabledProviderList = uiState.disabledProviders,
                        onBackButtonSelected = viewModel::onBackButtonSelected,
                        onOptionSelected = viewModel::onEntrySelectedFromMoreOptionScreen,
                        onDisabledPasswordManagerSelected =
                        viewModel::onDisabledPasswordManagerSelected,
                        onRemoteEntrySelected = viewModel::onEntrySelected,
                    )
                    CreateScreenState.MORE_OPTIONS_ROW_INTRO -> MoreOptionsRowIntroCard(
                        providerInfo = uiState.activeEntry?.activeProvider!!,
                        onDefaultOrNotSelected = viewModel::onDefaultOrNotSelected
                    )
                    CreateScreenState.EXTERNAL_ONLY_SELECTION -> ExternalOnlySelectionCard(
                        requestDisplayInfo = uiState.requestDisplayInfo,
                        activeRemoteEntry = uiState.activeEntry?.activeEntryInfo!!,
                        onOptionSelected = viewModel::onEntrySelected,
                        onConfirm = viewModel::onConfirmEntrySelected,
                        onCancel = viewModel::onCancel,
                    )
                }
            } else if (uiState.selectedEntry != null && !uiState.providerActivityPending) {
                viewModel.launchProviderUi(providerActivityLauncher)
            }
        },
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f),
        sheetShape = EntryShape.TopRoundedCorner,
    ) {}
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == ModalBottomSheetValue.Hidden) {
            viewModel.onCancel()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationCard(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ContainerCard() {
        Column() {
            Image(
                painter = painterResource(R.drawable.ic_passkeys_onboarding),
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
                    modifier = Modifier.padding(start = 16.dp),
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
                    modifier = Modifier.padding(start = 16.dp),
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
                    modifier = Modifier.padding(start = 16.dp),
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
                CancelButton(
                    stringResource(R.string.string_cancel),
                    onClick = onCancel
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    enabledProviderList: List<EnabledProviderInfo>,
    disabledProviderList: List<DisabledProviderInfo>?,
    onOptionSelected: (ActiveEntry) -> Unit,
    onDisabledPasswordManagerSelected: () -> Unit,
    onCancel: () -> Unit,
    onRemoteEntrySelected: (EntryInfo) -> Unit,
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
                        TYPE_PUBLIC_KEY_CREDENTIAL -> stringResource(R.string.create_your_passkeys)
                        TYPE_PASSWORD_CREDENTIAL -> stringResource(R.string.save_your_password)
                        else -> stringResource(R.string.save_your_sign_in_info)
                    },
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
            Divider(
                thickness = 18.dp,
                color = Color.Transparent
            )
            ContainerCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    enabledProviderList.forEach { enabledProviderInfo ->
                        enabledProviderInfo.createOptions.forEach { createOptionInfo ->
                            item {
                                MoreOptionsInfoRow(
                                    providerInfo = enabledProviderInfo,
                                    createOptionInfo = createOptionInfo,
                                    onOptionSelected = {
                                        onOptionSelected(
                                            ActiveEntry(
                                                enabledProviderInfo,
                                                createOptionInfo
                                            )
                                        )
                                    })
                            }
                        }
                    }
                    if (disabledProviderList != null) {
                        item {
                            MoreOptionsDisabledProvidersRow(
                                disabledProviders = disabledProviderList,
                                onDisabledPasswordManagerSelected =
                                onDisabledPasswordManagerSelected,
                            )
                        }
                    }
                }
            }
            // TODO: handle the error situation that if multiple remoteInfos exists
            enabledProviderList.forEach { enabledProvider ->
                if (enabledProvider.remoteEntry != null) {
                    TextButton(
                        onClick = {
                            onRemoteEntrySelected(enabledProvider.remoteEntry!!)
                        },
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .align(alignment = Alignment.CenterHorizontally),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.string_save_to_another_device),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Divider(
                thickness = 24.dp,
                color = Color.Transparent
            )
            Row(
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                CancelButton(stringResource(R.string.string_cancel), onCancel)
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
fun MoreOptionsSelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    enabledProviderList: List<EnabledProviderInfo>,
    disabledProviderList: List<DisabledProviderInfo>?,
    onBackButtonSelected: () -> Unit,
    onOptionSelected: (ActiveEntry) -> Unit,
    onDisabledPasswordManagerSelected: () -> Unit,
    onRemoteEntrySelected: (EntryInfo) -> Unit,
) {
    ContainerCard() {
        Column() {
            TopAppBar(
                title = {
                    TextOnSurface(
                        text = when (requestDisplayInfo.type) {
                            TYPE_PUBLIC_KEY_CREDENTIAL ->
                                stringResource(R.string.create_passkey_in_title)
                            TYPE_PASSWORD_CREDENTIAL ->
                                stringResource(R.string.save_password_to_title)
                            else -> stringResource(R.string.save_sign_in_to_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackButtonSelected) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            stringResource(R.string.accessibility_back_arrow_button)
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors
                    (containerColor = Color.Transparent),
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
                    enabledProviderList.forEach { enabledProviderInfo ->
                        enabledProviderInfo.createOptions.forEach { createOptionInfo ->
                            item {
                                MoreOptionsInfoRow(
                                    providerInfo = enabledProviderInfo,
                                    createOptionInfo = createOptionInfo,
                                    onOptionSelected = {
                                        onOptionSelected(
                                            ActiveEntry(
                                                enabledProviderInfo,
                                                createOptionInfo
                                            )
                                        )
                                    })
                            }
                        }
                    }
                    if (disabledProviderList != null) {
                        item {
                            MoreOptionsDisabledProvidersRow(
                                disabledProviders = disabledProviderList,
                                onDisabledPasswordManagerSelected =
                                onDisabledPasswordManagerSelected,
                            )
                        }
                    }
                    // TODO: handle the error situation that if multiple remoteInfos exists
                    enabledProviderList.forEach {
                        if (it.remoteEntry != null) {
                            item {
                                RemoteEntryRow(
                                    remoteInfo = it.remoteEntry!!,
                                    onRemoteEntrySelected = onRemoteEntrySelected,
                                )
                            }
                        }
                    }
                }
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
fun MoreOptionsRowIntroCard(
    providerInfo: EnabledProviderInfo,
    onDefaultOrNotSelected: () -> Unit,
) {
    ContainerCard() {
        Column() {
            Icon(
                Icons.Outlined.NewReleases,
                contentDescription = null,
                modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                    .padding(all = 24.dp)
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
                CancelButton(
                    stringResource(R.string.use_once),
                    onClick = onDefaultOrNotSelected
                )
                ConfirmButton(
                    stringResource(R.string.set_as_default),
                    onClick = onDefaultOrNotSelected
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
    showActiveEntryOnly: Boolean,
    onOptionSelected: (EntryInfo) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onMoreOptionsSelected: () -> Unit,
) {
    ContainerCard() {
        Column() {
            Icon(
                bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                    .padding(all = 24.dp).size(32.dp)
            )
            TextOnSurface(
                text = when (requestDisplayInfo.type) {
                    TYPE_PUBLIC_KEY_CREDENTIAL -> stringResource(
                        R.string.choose_create_option_passkey_title,
                        providerInfo.displayName
                    )
                    TYPE_PASSWORD_CREDENTIAL -> stringResource(
                        R.string.choose_create_option_password_title,
                        providerInfo.displayName
                    )
                    else -> stringResource(
                        R.string.choose_create_option_sign_in_title,
                        providerInfo.displayName
                    )
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
            )
            if (createOptionInfo.userProviderDisplayName != null) {
                TextSecondary(
                    text = stringResource(
                        R.string.choose_create_option_description,
                        requestDisplayInfo.appDomainName,
                        when (requestDisplayInfo.type) {
                            TYPE_PUBLIC_KEY_CREDENTIAL -> stringResource(R.string.passkey)
                            TYPE_PASSWORD_CREDENTIAL -> stringResource(R.string.password)
                            else -> stringResource(R.string.sign_ins)
                        },
                        providerInfo.displayName,
                        createOptionInfo.userProviderDisplayName
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(all = 24.dp)
                        .align(alignment = Alignment.CenterHorizontally),
                )
            }
            ContainerCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
            ) {
                PrimaryCreateOptionRow(
                    requestDisplayInfo = requestDisplayInfo,
                    entryInfo = createOptionInfo,
                    onOptionSelected = onOptionSelected
                )
            }
            if (!showActiveEntryOnly) {
                var createOptionsSize = 0
                enabledProviderList.forEach { enabledProvider ->
                    createOptionsSize += enabledProvider.createOptions.size
                }
                if (createOptionsSize > 1) {
                    TextButton(
                        onClick = onMoreOptionsSelected,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .align(alignment = Alignment.CenterHorizontally),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            text =
                            when (requestDisplayInfo.type) {
                                TYPE_PUBLIC_KEY_CREDENTIAL ->
                                    stringResource(R.string.string_create_in_another_place)
                                else -> stringResource(R.string.string_save_to_another_place)
                            },
                            textAlign = TextAlign.Center,
                        )
                    }
                } else if (
                    requestDisplayInfo.type == TYPE_PUBLIC_KEY_CREDENTIAL
                ) {
                    // TODO: handle the error situation that if multiple remoteInfos exists
                    enabledProviderList.forEach { enabledProvider ->
                        if (enabledProvider.remoteEntry != null) {
                            TextButton(
                                onClick = {
                                    onOptionSelected(enabledProvider.remoteEntry!!)
                                },
                                modifier = Modifier
                                    .padding(horizontal = 24.dp)
                                    .align(alignment = Alignment.CenterHorizontally),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text(
                                    text = stringResource(R.string.string_use_another_device),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            Divider(
                thickness = 24.dp,
                color = Color.Transparent
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                CancelButton(
                    stringResource(R.string.string_cancel),
                    onClick = onCancel
                )
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
fun ExternalOnlySelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    activeRemoteEntry: EntryInfo,
    onOptionSelected: (EntryInfo) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ContainerCard() {
        Column() {
            Icon(
                painter = painterResource(R.drawable.ic_other_devices),
                contentDescription = null,
                tint = Color.Unspecified,
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
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                CancelButton(
                    stringResource(R.string.string_cancel),
                    onClick = onCancel
                )
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
fun PrimaryCreateOptionRow(
    requestDisplayInfo: RequestDisplayInfo,
    entryInfo: EntryInfo,
    onOptionSelected: (EntryInfo) -> Unit
) {
    Entry(
        onClick = { onOptionSelected(entryInfo) },
        icon = {
            Icon(
                bitmap = if (entryInfo is CreateOptionInfo) {
                    entryInfo.profileIcon.toBitmap().asImageBitmap()
                } else {
                    requestDisplayInfo.typeIcon.toBitmap().asImageBitmap()
                },
                contentDescription = null,
                tint = LocalAndroidColorScheme.current.colorAccentPrimaryVariant,
                modifier = Modifier.padding(horizontal = 18.dp).size(32.dp)
            )
        },
        label = {
            Column() {
                // TODO: Add the function to hide/view password when the type is create password
                when (requestDisplayInfo.type) {
                    TYPE_PUBLIC_KEY_CREDENTIAL -> {
                        TextOnSurfaceVariant(
                            text = requestDisplayInfo.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        TextSecondary(
                            text = if (requestDisplayInfo.subtitle != null) {
                                stringResource(
                                    R.string.passkey_before_subtitle
                                ) + " - " + requestDisplayInfo.subtitle
                            } else {
                                stringResource(R.string.passkey_before_subtitle)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                    TYPE_PASSWORD_CREDENTIAL -> {
                        TextOnSurfaceVariant(
                            text = requestDisplayInfo.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        TextSecondary(
                            // This subtitle would never be null for create password
                            text = requestDisplayInfo.subtitle ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                    else -> {
                        TextOnSurfaceVariant(
                            text = requestDisplayInfo.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsInfoRow(
    providerInfo: EnabledProviderInfo,
    createOptionInfo: CreateOptionInfo,
    onOptionSelected: () -> Unit
) {
    Entry(
        onClick = onOptionSelected,
        icon = {
            Image(
                modifier = Modifier.padding(horizontal = 16.dp).size(32.dp),
                bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
                contentDescription = null
            )
        },
        label = {
            Column() {
                TextOnSurfaceVariant(
                    text = providerInfo.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (createOptionInfo.userProviderDisplayName != null) {
                    TextSecondary(
                        text = createOptionInfo.userProviderDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                } else if (createOptionInfo.passwordCount != null) {
                    TextSecondary(
                        text =
                        stringResource(
                            R.string.more_options_usage_passwords,
                            createOptionInfo.passwordCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                } else if (createOptionInfo.passkeyCount != null) {
                    TextSecondary(
                        text =
                        stringResource(
                            R.string.more_options_usage_passkeys,
                            createOptionInfo.passkeyCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                } else if (createOptionInfo.totalCredentialCount != null) {
                    // TODO: Handle the case when there is total count
                    // but no passwords and passkeys after design is set
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsDisabledProvidersRow(
    disabledProviders: List<ProviderInfo>,
    onDisabledPasswordManagerSelected: () -> Unit,
) {
    Entry(
        onClick = onDisabledPasswordManagerSelected,
        icon = {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.padding(start = 16.dp)
            )
        },
        label = {
            Column() {
                TextOnSurfaceVariant(
                    text = stringResource(R.string.other_password_manager),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp, start = 16.dp),
                )
                // TODO: Update the subtitle once design is confirmed
                TextSecondary(
                    text = disabledProviders.joinToString(separator = ", ") { it.displayName },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp, start = 16.dp),
                )
            }
        }
    )
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
                tint = Color.Unspecified,
                modifier = Modifier.padding(start = 18.dp)
            )
        },
        label = {
            Column() {
                TextOnSurfaceVariant(
                    text = stringResource(R.string.another_device),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 18.dp)
                        .align(alignment = Alignment.CenterHorizontally),
                )
            }
        }
    )
}