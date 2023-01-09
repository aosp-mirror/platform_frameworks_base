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

package com.android.credentialmanager.getflow

import android.credentials.Credential
import android.text.TextUtils
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.android.credentialmanager.common.ui.ActionButton
import com.android.credentialmanager.common.ui.Entry
import com.android.credentialmanager.common.ui.TextOnSurface
import com.android.credentialmanager.common.ui.TextSecondary
import com.android.credentialmanager.common.ui.TextOnSurfaceVariant
import com.android.credentialmanager.common.ui.ContainerCard
import com.android.credentialmanager.common.ui.TransparentBackgroundEntry
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential
import com.android.credentialmanager.ui.theme.EntryShape
import com.android.credentialmanager.ui.theme.LocalAndroidColorScheme

@Composable
fun GetCredentialScreen(
    viewModel: GetCredentialViewModel,
    providerActivityLauncher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
) {
    val state = rememberModalBottomSheetState(
        initialValue = ModalBottomSheetValue.Expanded,
        skipHalfExpanded = true
    )
    val uiState = viewModel.uiState
    if (uiState.currentScreenState != GetScreenState.REMOTE_ONLY) {
        ModalBottomSheetLayout(
            sheetBackgroundColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.background(Color.Transparent),
            sheetState = state,
            sheetContent = {
                // TODO: hide UI at top level
                if (!uiState.hidden) {
                    if (uiState.currentScreenState == GetScreenState.PRIMARY_SELECTION) {
                        PrimarySelectionCard(
                            requestDisplayInfo = uiState.requestDisplayInfo,
                            providerDisplayInfo = uiState.providerDisplayInfo,
                            onEntrySelected = viewModel::onEntrySelected,
                            onMoreOptionSelected = viewModel::onMoreOptionSelected,
                        )
                    } else {
                        AllSignInOptionCard(
                            providerInfoList = uiState.providerInfoList,
                            providerDisplayInfo = uiState.providerDisplayInfo,
                            onEntrySelected = viewModel::onEntrySelected,
                            onBackButtonClicked = viewModel::onBackToPrimarySelectionScreen,
                            onCancel = viewModel::onCancel,
                            isNoAccount = uiState.isNoAccount,
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
    } else {
        SnackBarScreen(
            onClick = viewModel::onMoreOptionOnSnackBarSelected,
            onCancel = viewModel::onCancel,
        )
    }
}

/** Draws the primary credential selection page. */
@Composable
fun PrimarySelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    providerDisplayInfo: ProviderDisplayInfo,
    onEntrySelected: (EntryInfo) -> Unit,
    onMoreOptionSelected: () -> Unit,
) {
    val sortedUserNameToCredentialEntryList =
        providerDisplayInfo.sortedUserNameToCredentialEntryList
    val authenticationEntryList = providerDisplayInfo.authenticationEntryList
    ContainerCard() {
        Column() {
            TextOnSurface(
                modifier = Modifier.padding(all = 24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
                text = stringResource(
                    if (sortedUserNameToCredentialEntryList
                            .size == 1 && authenticationEntryList.isEmpty()
                    ) {
                        if (sortedUserNameToCredentialEntryList.first()
                                .sortedCredentialEntryList.first().credentialType
                            == PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL
                        ) R.string.get_dialog_title_use_passkey_for
                        else R.string.get_dialog_title_use_sign_in_for
                    } else if (
                        sortedUserNameToCredentialEntryList
                            .isEmpty() && authenticationEntryList.size == 1
                    ) {
                        R.string.get_dialog_title_use_sign_in_for
                    } else R.string.get_dialog_title_choose_sign_in_for,
                    requestDisplayInfo.appName
                ),
            )

            ContainerCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally)
            ) {
                val usernameForCredentialSize = sortedUserNameToCredentialEntryList
                    .size
                val authenticationEntrySize = authenticationEntryList.size
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Show max 4 entries in this primary page
                    if (usernameForCredentialSize + authenticationEntrySize <= 4) {
                        items(sortedUserNameToCredentialEntryList) {
                            CredentialEntryRow(
                                credentialEntryInfo = it.sortedCredentialEntryList.first(),
                                onEntrySelected = onEntrySelected,
                            )
                        }
                        items(authenticationEntryList) {
                            AuthenticationEntryRow(
                                authenticationEntryInfo = it,
                                onEntrySelected = onEntrySelected,
                            )
                        }
                    } else if (usernameForCredentialSize < 4) {
                        items(sortedUserNameToCredentialEntryList) {
                            CredentialEntryRow(
                                credentialEntryInfo = it.sortedCredentialEntryList.first(),
                                onEntrySelected = onEntrySelected,
                            )
                        }
                        items(authenticationEntryList.take(4 - usernameForCredentialSize)) {
                            AuthenticationEntryRow(
                                authenticationEntryInfo = it,
                                onEntrySelected = onEntrySelected,
                            )
                        }
                    } else {
                        items(sortedUserNameToCredentialEntryList.take(4)) {
                            CredentialEntryRow(
                                credentialEntryInfo = it.sortedCredentialEntryList.first(),
                                onEntrySelected = onEntrySelected,
                            )
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
                ActionButton(
                    stringResource(R.string.get_dialog_use_saved_passkey_for),
                    onMoreOptionSelected)
            }
            Divider(
                thickness = 18.dp,
                color = Color.Transparent,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

/** Draws the secondary credential selection page, where all sign-in options are listed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllSignInOptionCard(
    providerInfoList: List<ProviderInfo>,
    providerDisplayInfo: ProviderDisplayInfo,
    onEntrySelected: (EntryInfo) -> Unit,
    onBackButtonClicked: () -> Unit,
    onCancel: () -> Unit,
    isNoAccount: Boolean,
) {
    val sortedUserNameToCredentialEntryList =
        providerDisplayInfo.sortedUserNameToCredentialEntryList
    val authenticationEntryList = providerDisplayInfo.authenticationEntryList
    ContainerCard() {
        Column() {
            TopAppBar(
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
                title = {
                    TextOnSurface(
                        text = stringResource(R.string.get_dialog_title_sign_in_options),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (isNoAccount) onCancel else onBackButtonClicked) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(
                                R.string.accessibility_back_arrow_button)
                        )
                    }
                },
                modifier = Modifier.padding(top = 12.dp)
            )

            ContainerCard(
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                    .align(alignment = Alignment.CenterHorizontally),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // For username
                    items(sortedUserNameToCredentialEntryList) { item ->
                        PerUserNameCredentials(
                            perUserNameCredentialEntryList = item,
                            onEntrySelected = onEntrySelected,
                        )
                    }
                    // Locked password manager
                    if (authenticationEntryList.isNotEmpty()) {
                        item {
                            LockedCredentials(
                                authenticationEntryList = authenticationEntryList,
                                onEntrySelected = onEntrySelected,
                            )
                        }
                    }
                    // From another device
                    val remoteEntry = providerDisplayInfo.remoteEntry
                    if (remoteEntry != null) {
                        item {
                            RemoteEntryCard(
                                remoteEntry = remoteEntry,
                                onEntrySelected = onEntrySelected,
                            )
                        }
                    }
                    // Manage sign-ins (action chips)
                    item {
                        ActionChips(
                            providerInfoList = providerInfoList,
                            onEntrySelected = onEntrySelected
                        )
                    }
                }
            }
        }
    }
}

// TODO: create separate rows for primary and secondary pages.
// TODO: reuse rows and columns across types.

@Composable
fun ActionChips(
    providerInfoList: List<ProviderInfo>,
    onEntrySelected: (EntryInfo) -> Unit,
) {
    val actionChips = providerInfoList.flatMap { it.actionEntryList }
    if (actionChips.isEmpty()) {
        return
    }

    TextSecondary(
        text = stringResource(R.string.get_dialog_heading_manage_sign_ins),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    // TODO: tweak padding.
    ContainerCard(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            actionChips.forEach {
                ActionEntryRow(it, onEntrySelected)
            }
        }
    }
}

@Composable
fun RemoteEntryCard(
    remoteEntry: RemoteEntryInfo,
    onEntrySelected: (EntryInfo) -> Unit,
) {
    TextSecondary(
        text = stringResource(R.string.get_dialog_heading_from_another_device),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    ContainerCard(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Entry(
                onClick = { onEntrySelected(remoteEntry) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_other_devices),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.padding(start = 18.dp)
                    )
                },
                label = {
                    TextOnSurfaceVariant(
                        text = stringResource(
                            R.string.get_dialog_option_headline_use_a_different_device),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 18.dp)
                            .align(alignment = Alignment.CenterHorizontally)
                    )
                }
            )
        }
    }
}

@Composable
fun LockedCredentials(
    authenticationEntryList: List<AuthenticationEntryInfo>,
    onEntrySelected: (EntryInfo) -> Unit,
) {
    TextSecondary(
        text = stringResource(R.string.get_dialog_heading_locked_password_managers),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    ContainerCard(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            authenticationEntryList.forEach {
                AuthenticationEntryRow(it, onEntrySelected)
            }
        }
    }
}

@Composable
fun PerUserNameCredentials(
    perUserNameCredentialEntryList: PerUserNameCredentialEntryList,
    onEntrySelected: (EntryInfo) -> Unit,
) {
    TextSecondary(
        text = stringResource(
            R.string.get_dialog_heading_for_username, perUserNameCredentialEntryList.userName
        ),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    ContainerCard(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            perUserNameCredentialEntryList.sortedCredentialEntryList.forEach {
                CredentialEntryRow(it, onEntrySelected)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialEntryRow(
    credentialEntryInfo: CredentialEntryInfo,
    onEntrySelected: (EntryInfo) -> Unit,
) {
    Entry(
        onClick = { onEntrySelected(credentialEntryInfo) },
        icon = {
            if (credentialEntryInfo.icon != null) {
                Image(
                    modifier = Modifier.padding(start = 10.dp).size(32.dp),
                    bitmap = credentialEntryInfo.icon.toBitmap().asImageBitmap(),
                    // TODO: add description.
                    contentDescription = "",
                )
            } else {
                Icon(
                    modifier = Modifier.padding(start = 10.dp).size(32.dp),
                    painter = painterResource(R.drawable.ic_other_sign_in),
                    // TODO: add description.
                    contentDescription = "",
                    tint = LocalAndroidColorScheme.current.colorAccentPrimaryVariant
                )
            }
        },
        label = {
            Column() {
                // TODO: fix the text values.
                TextOnSurfaceVariant(
                    text = credentialEntryInfo.userName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp, start = 5.dp)
                )
                TextSecondary(
                    text = if (
                        credentialEntryInfo.credentialType == Credential.TYPE_PASSWORD_CREDENTIAL) {
                        "••••••••••••"
                    } else {
                        if (TextUtils.isEmpty(credentialEntryInfo.displayName))
                            credentialEntryInfo.credentialTypeDisplayName
                        else
                            credentialEntryInfo.credentialTypeDisplayName +
                                    stringResource(
                                        R.string.get_dialog_sign_in_type_username_separator
                                    ) +
                                    credentialEntryInfo.displayName
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp, start = 5.dp)
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticationEntryRow(
    authenticationEntryInfo: AuthenticationEntryInfo,
    onEntrySelected: (EntryInfo) -> Unit,
) {
    Entry(
        onClick = { onEntrySelected(authenticationEntryInfo) },
        icon = {
            Image(
                modifier = Modifier.padding(start = 10.dp).size(32.dp),
                bitmap = authenticationEntryInfo.icon.toBitmap().asImageBitmap(),
                // TODO: add description.
                contentDescription = ""
            )
        },
        label = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp),
                ) {
                Column() {
                    // TODO: fix the text values.
                    TextOnSurfaceVariant(
                        text = authenticationEntryInfo.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    TextSecondary(
                        text = stringResource(R.string.locked_credential_entry_label_subtext),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
                Icon(
                    Icons.Outlined.Lock,
                    null,
                    Modifier.align(alignment = Alignment.CenterVertically).padding(end = 10.dp),
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionEntryRow(
    actionEntryInfo: ActionEntryInfo,
    onEntrySelected: (EntryInfo) -> Unit,
) {
    TransparentBackgroundEntry(
        icon = {
            Image(
                modifier = Modifier.padding(start = 10.dp).size(32.dp),
                bitmap = actionEntryInfo.icon.toBitmap().asImageBitmap(),
                // TODO: add description.
                contentDescription = ""
            )
        },
        label = {
            Column() {
                TextOnSurfaceVariant(
                    text = actionEntryInfo.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 5.dp),
                )
                if (actionEntryInfo.subTitle != null) {
                    TextSecondary(
                        text = actionEntryInfo.subTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        },
        onClick = { onEntrySelected(actionEntryInfo) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnackBarScreen(
    onClick: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // TODO: Change the height, width and position according to the design
    Snackbar(
        modifier = Modifier.padding(horizontal = 40.dp).padding(top = 700.dp),
        shape = EntryShape.FullMediumRoundedCorner,
        containerColor = LocalAndroidColorScheme.current.colorBackground,
        contentColor = LocalAndroidColorScheme.current.colorAccentPrimaryVariant,
        action = {
            TextButton(
                onClick = { onClick(true) },
            ) {
                Text(text = stringResource(R.string.snackbar_action))
            }
        },
        dismissAction = {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.accessibility_close_button
                    ),
                    tint = LocalAndroidColorScheme.current.colorAccentTertiary
                )
            }
        },
    ) {
        Text(text = stringResource(R.string.get_dialog_use_saved_passkey_for))
    }
}