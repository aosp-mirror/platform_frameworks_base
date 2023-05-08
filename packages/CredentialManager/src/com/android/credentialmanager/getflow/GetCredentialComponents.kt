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

import android.graphics.drawable.Drawable
import android.text.TextUtils
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Divider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.CredentialSelectorViewModel
import com.android.credentialmanager.R
import com.android.credentialmanager.common.BaseEntry
import com.android.credentialmanager.common.CredentialType
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.common.ui.ActionButton
import com.android.credentialmanager.common.ui.ActionEntry
import com.android.credentialmanager.common.ui.ConfirmButton
import com.android.credentialmanager.common.ui.CredentialContainerCard
import com.android.credentialmanager.common.ui.CtaButtonRow
import com.android.credentialmanager.common.ui.Entry
import com.android.credentialmanager.common.ui.ModalBottomSheet
import com.android.credentialmanager.common.ui.MoreOptionTopAppBar
import com.android.credentialmanager.common.ui.SheetContainerCard
import com.android.credentialmanager.common.ui.SnackbarActionText
import com.android.credentialmanager.common.ui.HeadlineText
import com.android.credentialmanager.common.ui.CredentialListSectionHeader
import com.android.credentialmanager.common.ui.HeadlineIcon
import com.android.credentialmanager.common.ui.LargeLabelTextOnSurfaceVariant
import com.android.credentialmanager.common.ui.Snackbar
import com.android.credentialmanager.logging.GetCredentialEvent
import com.android.credentialmanager.userAndDisplayNameForPasskey
import com.android.internal.logging.UiEventLogger.UiEventEnum

@Composable
fun GetCredentialScreen(
    viewModel: CredentialSelectorViewModel,
    getCredentialUiState: GetCredentialUiState,
    providerActivityLauncher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
) {
    if (getCredentialUiState.currentScreenState == GetScreenState.REMOTE_ONLY) {
        RemoteCredentialSnackBarScreen(
            onClick = viewModel::getFlowOnMoreOptionOnSnackBarSelected,
            onCancel = viewModel::onUserCancel,
            onLog = { viewModel.logUiEvent(it) },
        )
        viewModel.uiMetrics.log(GetCredentialEvent.CREDMAN_GET_CRED_SCREEN_REMOTE_ONLY)
    } else if (getCredentialUiState.currentScreenState
        == GetScreenState.UNLOCKED_AUTH_ENTRIES_ONLY) {
        EmptyAuthEntrySnackBarScreen(
            authenticationEntryList =
            getCredentialUiState.providerDisplayInfo.authenticationEntryList,
            onCancel = viewModel::silentlyFinishActivity,
            onLastLockedAuthEntryNotFound = viewModel::onLastLockedAuthEntryNotFoundError,
            onLog = { viewModel.logUiEvent(it) },
        )
        viewModel.uiMetrics.log(GetCredentialEvent
                .CREDMAN_GET_CRED_SCREEN_UNLOCKED_AUTH_ENTRIES_ONLY)
    } else {
        ModalBottomSheet(
            sheetContent = {
                // Hide the sheet content as opposed to the whole bottom sheet to maintain the scrim
                // background color even when the content should be hidden while waiting for
                // results from the provider app.
                when (viewModel.uiState.providerActivityState) {
                    ProviderActivityState.NOT_APPLICABLE -> {
                        if (getCredentialUiState.currentScreenState
                            == GetScreenState.PRIMARY_SELECTION) {
                            PrimarySelectionCard(
                                requestDisplayInfo = getCredentialUiState.requestDisplayInfo,
                                providerDisplayInfo = getCredentialUiState.providerDisplayInfo,
                                providerInfoList = getCredentialUiState.providerInfoList,
                                activeEntry = getCredentialUiState.activeEntry,
                                onEntrySelected = viewModel::getFlowOnEntrySelected,
                                onConfirm = viewModel::getFlowOnConfirmEntrySelected,
                                onMoreOptionSelected = viewModel::getFlowOnMoreOptionSelected,
                                onLog = { viewModel.logUiEvent(it) },
                            )
                            viewModel.uiMetrics.log(GetCredentialEvent
                                    .CREDMAN_GET_CRED_SCREEN_PRIMARY_SELECTION)
                        } else {
                            AllSignInOptionCard(
                                providerInfoList = getCredentialUiState.providerInfoList,
                                providerDisplayInfo = getCredentialUiState.providerDisplayInfo,
                                onEntrySelected = viewModel::getFlowOnEntrySelected,
                                onBackButtonClicked =
                                if (getCredentialUiState.isNoAccount)
                                    viewModel::getFlowOnBackToHybridSnackBarScreen
                                else viewModel::getFlowOnBackToPrimarySelectionScreen,
                                onCancel = viewModel::onUserCancel,
                                onLog = { viewModel.logUiEvent(it) },
                            )
                            viewModel.uiMetrics.log(GetCredentialEvent
                                    .CREDMAN_GET_CRED_SCREEN_ALL_SIGN_IN_OPTIONS)
                        }
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
            isInitialRender = viewModel.uiState.isInitialRender,
            isAutoSelectFlow = viewModel.uiState.isAutoSelectFlow,
            onInitialRenderComplete = viewModel::onInitialRenderComplete,
        )
    }
}

/** Draws the primary credential selection page. */
@Composable
fun PrimarySelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    providerDisplayInfo: ProviderDisplayInfo,
    providerInfoList: List<ProviderInfo>,
    activeEntry: BaseEntry?,
    onEntrySelected: (BaseEntry) -> Unit,
    onConfirm: () -> Unit,
    onMoreOptionSelected: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    val showMoreForTruncatedEntry = remember { mutableStateOf(false) }
    val sortedUserNameToCredentialEntryList =
        providerDisplayInfo.sortedUserNameToCredentialEntryList
    val authenticationEntryList = providerDisplayInfo.authenticationEntryList
    SheetContainerCard {
        val preferTopBrandingContent = requestDisplayInfo.preferTopBrandingContent
        if (preferTopBrandingContent != null) {
            item {
                HeadlineProviderIconAndName(
                    preferTopBrandingContent.icon,
                    preferTopBrandingContent.displayName
                )
            }
        } else {
            // When only one provider (not counting the remote-only provider) exists, display that
            // provider's icon + name up top.
            val providersWithActualEntries = providerInfoList.filter {
                it.credentialEntryList.isNotEmpty() || it.authenticationEntryList.isNotEmpty()
            }
            if (providersWithActualEntries.size == 1) {
                // First should always work but just to be safe.
                val providerInfo = providersWithActualEntries.firstOrNull()
                if (providerInfo != null) {
                    item {
                        HeadlineProviderIconAndName(
                            providerInfo.icon,
                            providerInfo.displayName
                        )
                    }
                }
            }
        }

        val hasSingleEntry = (sortedUserNameToCredentialEntryList.size == 1 &&
            authenticationEntryList.isEmpty()) || (sortedUserNameToCredentialEntryList.isEmpty() &&
            authenticationEntryList.size == 1)
        item {
            if (requestDisplayInfo.preferIdentityDocUi) {
                HeadlineText(
                    text = stringResource(
                        if (hasSingleEntry) {
                            R.string.get_dialog_title_use_info_on
                        } else {
                            R.string.get_dialog_title_choose_option_for
                        },
                        requestDisplayInfo.appName
                    ),
                )
            } else {
                HeadlineText(
                    text = stringResource(
                        if (hasSingleEntry) {
                            val singleEntryType = sortedUserNameToCredentialEntryList.firstOrNull()
                                ?.sortedCredentialEntryList?.firstOrNull()?.credentialType
                            if (singleEntryType == CredentialType.PASSKEY)
                                R.string.get_dialog_title_use_passkey_for
                            else if (singleEntryType == CredentialType.PASSWORD)
                                R.string.get_dialog_title_use_password_for
                            else if (authenticationEntryList.isNotEmpty())
                                R.string.get_dialog_title_unlock_options_for
                            else R.string.get_dialog_title_use_sign_in_for
                        } else {
                            if (authenticationEntryList.isNotEmpty() ||
                                sortedUserNameToCredentialEntryList.any { perNameEntryList ->
                                    perNameEntryList.sortedCredentialEntryList.any { entry ->
                                        entry.credentialType != CredentialType.PASSWORD &&
                                            entry.credentialType != CredentialType.PASSKEY
                                    }
                                }
                            )
                                R.string.get_dialog_title_choose_sign_in_for
                            else
                                R.string.get_dialog_title_choose_saved_sign_in_for
                        },
                        requestDisplayInfo.appName
                    ),
                )
            }
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        item {
            CredentialContainerCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val usernameForCredentialSize = sortedUserNameToCredentialEntryList.size
                    val authenticationEntrySize = authenticationEntryList.size
                    // If true, render a view more button for the single truncated entry on the
                    // front page.
                    // Show max 4 entries in this primary page
                    if (usernameForCredentialSize + authenticationEntrySize <= 4) {
                        sortedUserNameToCredentialEntryList.forEach {
                            CredentialEntryRow(
                                credentialEntryInfo = it.sortedCredentialEntryList.first(),
                                onEntrySelected = onEntrySelected,
                                enforceOneLine = true,
                                onTextLayout = {
                                    showMoreForTruncatedEntry.value = it.hasVisualOverflow
                                }
                            )
                        }
                        authenticationEntryList.forEach {
                            AuthenticationEntryRow(
                                authenticationEntryInfo = it,
                                onEntrySelected = onEntrySelected,
                                enforceOneLine = true,
                            )
                        }
                    } else if (usernameForCredentialSize < 4) {
                        sortedUserNameToCredentialEntryList.forEach {
                            CredentialEntryRow(
                                credentialEntryInfo = it.sortedCredentialEntryList.first(),
                                onEntrySelected = onEntrySelected,
                                enforceOneLine = true,
                            )
                        }
                        authenticationEntryList.take(4 - usernameForCredentialSize).forEach {
                            AuthenticationEntryRow(
                                authenticationEntryInfo = it,
                                onEntrySelected = onEntrySelected,
                                enforceOneLine = true,
                            )
                        }
                    } else {
                        sortedUserNameToCredentialEntryList.take(4).forEach {
                            CredentialEntryRow(
                                credentialEntryInfo = it.sortedCredentialEntryList.first(),
                                onEntrySelected = onEntrySelected,
                                enforceOneLine = true,
                            )
                        }
                    }
                }
            }
        }
        item { Divider(thickness = 24.dp, color = Color.Transparent) }
        var totalEntriesCount = sortedUserNameToCredentialEntryList
            .flatMap { it.sortedCredentialEntryList }.size + authenticationEntryList
            .size + providerInfoList.flatMap { it.actionEntryList }.size
        if (providerDisplayInfo.remoteEntry != null) totalEntriesCount += 1
        // Row horizontalArrangement differs on only one actionButton(should place on most
        // left)/only one confirmButton(should place on most right)/two buttons exist the same
        // time(should be one on the left, one on the right)
        item {
            CtaButtonRow(
                leftButton = if (totalEntriesCount > 1) {
                    {
                        ActionButton(
                            stringResource(R.string.get_dialog_title_sign_in_options),
                            onMoreOptionSelected
                        )
                    }
                } else if (showMoreForTruncatedEntry.value) {
                    {
                        ActionButton(
                            stringResource(R.string.button_label_view_more),
                            onMoreOptionSelected
                        )
                    }
                } else null,
                rightButton = if (activeEntry != null) { // Only one sign-in options exist
                    {
                        ConfirmButton(
                            stringResource(R.string.string_continue),
                            onClick = onConfirm
                        )
                    }
                } else null,
            )
        }
    }
    onLog(GetCredentialEvent.CREDMAN_GET_CRED_PRIMARY_SELECTION_CARD)
}

/** Draws the secondary credential selection page, where all sign-in options are listed. */
@Composable
fun AllSignInOptionCard(
    providerInfoList: List<ProviderInfo>,
    providerDisplayInfo: ProviderDisplayInfo,
    onEntrySelected: (BaseEntry) -> Unit,
    onBackButtonClicked: () -> Unit,
    onCancel: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    val sortedUserNameToCredentialEntryList =
        providerDisplayInfo.sortedUserNameToCredentialEntryList
    val authenticationEntryList = providerDisplayInfo.authenticationEntryList
    SheetContainerCard(topAppBar = {
        MoreOptionTopAppBar(
            text = stringResource(R.string.get_dialog_title_sign_in_options),
            onNavigationIconClicked = onBackButtonClicked,
            bottomPadding = 0.dp,
        )
    }) {
        var isFirstSection = true
        // For username
        items(sortedUserNameToCredentialEntryList) { item ->
            PerUserNameCredentials(
                perUserNameCredentialEntryList = item,
                onEntrySelected = onEntrySelected,
                isFirstSection = isFirstSection,
            )
            isFirstSection = false
        }
        // Locked password manager
        if (authenticationEntryList.isNotEmpty()) {
            item {
                LockedCredentials(
                    authenticationEntryList = authenticationEntryList,
                    onEntrySelected = onEntrySelected,
                    isFirstSection = isFirstSection,
                )
                isFirstSection = false
            }
        }
        // From another device
        val remoteEntry = providerDisplayInfo.remoteEntry
        if (remoteEntry != null) {
            item {
                RemoteEntryCard(
                    remoteEntry = remoteEntry,
                    onEntrySelected = onEntrySelected,
                    isFirstSection = isFirstSection,
                )
                isFirstSection = false
            }
        }
        // Manage sign-ins (action chips)
        item {
            ActionChips(
                providerInfoList = providerInfoList,
                onEntrySelected = onEntrySelected,
                isFirstSection = isFirstSection,
            )
            isFirstSection = false
        }
    }
    onLog(GetCredentialEvent.CREDMAN_GET_CRED_ALL_SIGN_IN_OPTION_CARD)
}

@Composable
fun HeadlineProviderIconAndName(
    icon: Drawable,
    name: String,
) {
    HeadlineIcon(
        bitmap = icon.toBitmap().asImageBitmap(),
        tint = Color.Unspecified,
    )
    Divider(thickness = 4.dp, color = Color.Transparent)
    LargeLabelTextOnSurfaceVariant(text = name)
    Divider(thickness = 16.dp, color = Color.Transparent)
}

@Composable
fun ActionChips(
    providerInfoList: List<ProviderInfo>,
    onEntrySelected: (BaseEntry) -> Unit,
    isFirstSection: Boolean,
) {
    val actionChips = providerInfoList.flatMap { it.actionEntryList }
    if (actionChips.isEmpty()) {
        return
    }

    CredentialListSectionHeader(
        text = stringResource(R.string.get_dialog_heading_manage_sign_ins),
        isFirstSection = isFirstSection,
    )
    CredentialContainerCard {
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
    onEntrySelected: (BaseEntry) -> Unit,
    isFirstSection: Boolean,
) {
    CredentialListSectionHeader(
        text = stringResource(R.string.get_dialog_heading_from_another_device),
        isFirstSection = isFirstSection,
    )
    CredentialContainerCard {
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Entry(
                onClick = { onEntrySelected(remoteEntry) },
                iconImageVector = Icons.Outlined.QrCodeScanner,
                entryHeadlineText = stringResource(
                    R.string.get_dialog_option_headline_use_a_different_device
                ),
            )
        }
    }
}

@Composable
fun LockedCredentials(
    authenticationEntryList: List<AuthenticationEntryInfo>,
    onEntrySelected: (BaseEntry) -> Unit,
    isFirstSection: Boolean,
) {
    CredentialListSectionHeader(
        text = stringResource(R.string.get_dialog_heading_locked_password_managers),
        isFirstSection = isFirstSection,
    )
    CredentialContainerCard {
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
    onEntrySelected: (BaseEntry) -> Unit,
    isFirstSection: Boolean,
) {
    CredentialListSectionHeader(
        text = stringResource(
            R.string.get_dialog_heading_for_username, perUserNameCredentialEntryList.userName
        ),
        isFirstSection = isFirstSection,
    )
    CredentialContainerCard {
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

@Composable
fun CredentialEntryRow(
    credentialEntryInfo: CredentialEntryInfo,
    onEntrySelected: (BaseEntry) -> Unit,
    enforceOneLine: Boolean = false,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val (username, displayName) = if (credentialEntryInfo.credentialType == CredentialType.PASSKEY)
        userAndDisplayNameForPasskey(
            credentialEntryInfo.userName, credentialEntryInfo.displayName ?: "")
    else Pair(credentialEntryInfo.userName, credentialEntryInfo.displayName)
    Entry(
        onClick = { onEntrySelected(credentialEntryInfo) },
        iconImageBitmap = credentialEntryInfo.icon?.toBitmap()?.asImageBitmap(),
        shouldApplyIconImageBitmapTint = credentialEntryInfo.shouldTintIcon,
        // Fall back to iconPainter if iconImageBitmap isn't available
        iconPainter =
        if (credentialEntryInfo.icon == null) painterResource(R.drawable.ic_other_sign_in_24)
        else null,
        entryHeadlineText = username,
        entrySecondLineText = if (
            credentialEntryInfo.credentialType == CredentialType.PASSWORD) {
            "••••••••••••"
        } else {
            val itemsToDisplay = listOf(
                displayName,
                credentialEntryInfo.credentialTypeDisplayName,
                credentialEntryInfo.providerDisplayName
            ).filterNot(TextUtils::isEmpty)
            if (itemsToDisplay.isEmpty()) null
            else itemsToDisplay.joinToString(
                separator = stringResource(R.string.get_dialog_sign_in_type_username_separator)
            )
        },
        enforceOneLine = enforceOneLine,
        onTextLayout = onTextLayout,
    )
}

@Composable
fun AuthenticationEntryRow(
    authenticationEntryInfo: AuthenticationEntryInfo,
    onEntrySelected: (BaseEntry) -> Unit,
    enforceOneLine: Boolean = false,
) {
    Entry(
        onClick = { onEntrySelected(authenticationEntryInfo) },
        iconImageBitmap = authenticationEntryInfo.icon.toBitmap().asImageBitmap(),
        entryHeadlineText = authenticationEntryInfo.title,
        entrySecondLineText = stringResource(
            if (authenticationEntryInfo.isUnlockedAndEmpty)
                R.string.locked_credential_entry_label_subtext_no_sign_in
            else R.string.locked_credential_entry_label_subtext_tap_to_unlock
        ),
        isLockedAuthEntry = !authenticationEntryInfo.isUnlockedAndEmpty,
        enforceOneLine = enforceOneLine,
    )
}

@Composable
fun ActionEntryRow(
    actionEntryInfo: ActionEntryInfo,
    onEntrySelected: (BaseEntry) -> Unit,
) {
    ActionEntry(
        iconImageBitmap = actionEntryInfo.icon.toBitmap().asImageBitmap(),
        entryHeadlineText = actionEntryInfo.title,
        entrySecondLineText = actionEntryInfo.subTitle,
        onClick = { onEntrySelected(actionEntryInfo) },
    )
}

@Composable
fun RemoteCredentialSnackBarScreen(
    onClick: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    Snackbar(
        action = {
            TextButton(
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 16.dp)
                    .heightIn(min = 32.dp),
                onClick = { onClick(true) },
                contentPadding =
                PaddingValues(start = 0.dp, top = 6.dp, end = 0.dp, bottom = 6.dp),
            ) {
                SnackbarActionText(text = stringResource(R.string.snackbar_action))
            }
        },
        onDismiss = onCancel,
        contentText = stringResource(R.string.get_dialog_use_saved_passkey_for),
    )
    onLog(GetCredentialEvent.CREDMAN_GET_CRED_REMOTE_CRED_SNACKBAR_SCREEN)
}

@Composable
fun EmptyAuthEntrySnackBarScreen(
    authenticationEntryList: List<AuthenticationEntryInfo>,
    onCancel: () -> Unit,
    onLastLockedAuthEntryNotFound: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    val lastLocked = authenticationEntryList.firstOrNull({ it.isLastUnlocked })
    if (lastLocked == null) {
        onLastLockedAuthEntryNotFound()
        return
    }

    Snackbar(
        onDismiss = onCancel,
        contentText = stringResource(R.string.no_sign_in_info_in, lastLocked.providerDisplayName),
    )
    onLog(GetCredentialEvent.CREDMAN_GET_CRED_SCREEN_EMPTY_AUTH_SNACKBAR_SCREEN)
}