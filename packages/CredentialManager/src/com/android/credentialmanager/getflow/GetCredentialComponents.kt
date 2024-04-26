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

import android.credentials.flags.Flags.credmanBiometricApiEnabled
import android.credentials.flags.Flags.selectorUiImprovementsEnabled
import android.graphics.drawable.Drawable
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.CredentialSelectorViewModel
import com.android.credentialmanager.R
import com.android.credentialmanager.common.BiometricError
import com.android.credentialmanager.common.BiometricFlowType
import com.android.credentialmanager.common.BiometricPromptState
import com.android.credentialmanager.common.ProviderActivityState
import com.android.credentialmanager.common.material.ModalBottomSheetDefaults
import com.android.credentialmanager.common.runBiometricFlowForGet
import com.android.credentialmanager.common.ui.ActionButton
import com.android.credentialmanager.common.ui.ActionEntry
import com.android.credentialmanager.common.ui.ConfirmButton
import com.android.credentialmanager.common.ui.CredentialContainerCard
import com.android.credentialmanager.common.ui.CredentialListSectionHeader
import com.android.credentialmanager.common.ui.CtaButtonRow
import com.android.credentialmanager.common.ui.Entry
import com.android.credentialmanager.common.ui.HeadlineIcon
import com.android.credentialmanager.common.ui.HeadlineText
import com.android.credentialmanager.common.ui.LargeLabelTextOnSurfaceVariant
import com.android.credentialmanager.common.ui.ModalBottomSheet
import com.android.credentialmanager.common.ui.MoreOptionTopAppBar
import com.android.credentialmanager.common.ui.SheetContainerCard
import com.android.credentialmanager.common.ui.Snackbar
import com.android.credentialmanager.common.ui.SnackbarActionText
import com.android.credentialmanager.logging.GetCredentialEvent
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.get.ActionEntryInfo
import com.android.credentialmanager.model.get.AuthenticationEntryInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.model.get.ProviderInfo
import com.android.credentialmanager.model.get.RemoteEntryInfo
import com.android.credentialmanager.userAndDisplayNameForPasskey
import com.android.internal.logging.UiEventLogger.UiEventEnum
import kotlin.math.max

@Composable
fun GetCredentialScreen(
    viewModel: CredentialSelectorViewModel,
    getCredentialUiState: GetCredentialUiState,
    providerActivityLauncher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>,
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
                            if (selectorUiImprovementsEnabled()) {
                                PrimarySelectionCardVImpl(
                                    requestDisplayInfo = getCredentialUiState.requestDisplayInfo,
                                    providerDisplayInfo = getCredentialUiState.providerDisplayInfo,
                                    providerInfoList = getCredentialUiState.providerInfoList,
                                    activeEntry = getCredentialUiState.activeEntry,
                                    onEntrySelected = viewModel::getFlowOnEntrySelected,
                                    onConfirm = viewModel::getFlowOnConfirmEntrySelected,
                                    onMoreOptionSelected = viewModel::getFlowOnMoreOptionSelected,
                                    onLog = { viewModel.logUiEvent(it) },
                                )
                            } else {
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
                            }
                            viewModel.uiMetrics.log(GetCredentialEvent
                                    .CREDMAN_GET_CRED_SCREEN_PRIMARY_SELECTION)
                        } else if (credmanBiometricApiEnabled() && getCredentialUiState
                                .currentScreenState == GetScreenState.BIOMETRIC_SELECTION) {
                            BiometricSelectionPage(
                                biometricEntry = getCredentialUiState.activeEntry,
                                onMoreOptionSelected = viewModel::getFlowOnMoreOptionOnlySelected,
                                onCancelFlowAndFinish = viewModel::onUserCancel,
                                onIllegalStateAndFinish = viewModel::onIllegalUiState,
                                requestDisplayInfo = getCredentialUiState.requestDisplayInfo,
                                providerInfoList = getCredentialUiState.providerInfoList,
                                providerDisplayInfo = getCredentialUiState.providerDisplayInfo,
                                onBiometricEntrySelected =
                                viewModel::getFlowOnEntrySelected,
                                fallbackToOriginalFlow =
                                viewModel::fallbackFromBiometricToNormalFlow,
                                getBiometricPromptState =
                                viewModel::getBiometricPromptStateStatus,
                                onBiometricPromptStateChange =
                                viewModel::onBiometricPromptStateChange,
                                getBiometricCancellationSignal =
                                viewModel::getBiometricCancellationSignal
                            )
                        } else if (credmanBiometricApiEnabled() &&
                                getCredentialUiState.currentScreenState
                                == GetScreenState.ALL_SIGN_IN_OPTIONS_ONLY) {
                            AllSignInOptionCard(
                                    providerInfoList = getCredentialUiState.providerInfoList,
                                    providerDisplayInfo = getCredentialUiState.providerDisplayInfo,
                                    onEntrySelected = viewModel::getFlowOnEntrySelected,
                                    onBackButtonClicked = viewModel::onUserCancel,
                                    onCancel = viewModel::onUserCancel,
                                    onLog = { viewModel.logUiEvent(it) },
                                    customTopBar = { MoreOptionTopAppBar(
                                            text = stringResource(
                                                    R.string.get_dialog_title_sign_in_options),
                                            onNavigationIconClicked = viewModel::onUserCancel,
                                            navigationIcon = Icons.Filled.Close,
                                            navigationIconContentDescription =
                                            stringResource(R.string.accessibility_close_button),
                                            bottomPadding = 0.dp
                                    ) }
                            )
                            viewModel.uiMetrics.log(GetCredentialEvent
                                    .CREDMAN_GET_CRED_SCREEN_ALL_SIGN_IN_OPTIONS)
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
                        viewModel.uiMetrics.log(GetCredentialEvent
                                .CREDMAN_GET_CRED_PROVIDER_ACTIVITY_READY_TO_LAUNCH)
                    }
                    ProviderActivityState.PENDING -> {
                        if (viewModel.uiState.isAutoSelectFlow) {
                            Divider(
                                thickness = Dp.Hairline, color = ModalBottomSheetDefaults.scrimColor
                            )
                        }
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

@Composable
internal fun BiometricSelectionPage(
    biometricEntry: EntryInfo?,
    onCancelFlowAndFinish: () -> Unit,
    onIllegalStateAndFinish: (String) -> Unit,
    onMoreOptionSelected: () -> Unit,
    requestDisplayInfo: RequestDisplayInfo,
    providerInfoList: List<ProviderInfo>,
    providerDisplayInfo: ProviderDisplayInfo,
    onBiometricEntrySelected: (
        EntryInfo,
        BiometricPrompt.AuthenticationResult?,
        BiometricError?
    ) -> Unit,
    fallbackToOriginalFlow: (BiometricFlowType) -> Unit,
    getBiometricPromptState: () -> BiometricPromptState,
    onBiometricPromptStateChange: (BiometricPromptState) -> Unit,
    getBiometricCancellationSignal: () -> CancellationSignal,
) {
    if (biometricEntry == null) {
        fallbackToOriginalFlow(BiometricFlowType.GET)
        return
    }
    runBiometricFlowForGet(
        biometricEntry = biometricEntry,
        context = LocalContext.current,
        openMoreOptionsPage = onMoreOptionSelected,
        sendDataToProvider = onBiometricEntrySelected,
        onCancelFlowAndFinish = onCancelFlowAndFinish,
        onIllegalStateAndFinish = onIllegalStateAndFinish,
        getBiometricPromptState = getBiometricPromptState,
        onBiometricPromptStateChange = onBiometricPromptStateChange,
        getRequestDisplayInfo = requestDisplayInfo,
        getProviderInfoList = providerInfoList,
        getProviderDisplayInfo = providerDisplayInfo,
        onBiometricFailureFallback = fallbackToOriginalFlow,
        getBiometricCancellationSignal = getBiometricCancellationSignal
    )
}

/** Draws the primary credential selection page, used in Android U. */
// TODO(b/327518384) - remove after flag selectorUiImprovementsEnabled is enabled.
@Composable
fun PrimarySelectionCard(
    requestDisplayInfo: RequestDisplayInfo,
    providerDisplayInfo: ProviderDisplayInfo,
    providerInfoList: List<ProviderInfo>,
    activeEntry: EntryInfo?,
    onEntrySelected: (EntryInfo) -> Unit,
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
                            generateDisplayTitleTextResCode(singleEntryType!!,
                                authenticationEntryList)
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

internal const val MAX_ENTRY_FOR_PRIMARY_PAGE = 4

/** Draws the primary credential selection page, used starting from android V. */
@Composable
fun PrimarySelectionCardVImpl(
    requestDisplayInfo: RequestDisplayInfo,
    providerDisplayInfo: ProviderDisplayInfo,
    providerInfoList: List<ProviderInfo>,
    activeEntry: EntryInfo?,
    onEntrySelected: (EntryInfo) -> Unit,
    onConfirm: () -> Unit,
    onMoreOptionSelected: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
) {
    val showMoreForTruncatedEntry = remember { mutableStateOf(false) }
    val sortedUserNameToCredentialEntryList =
        providerDisplayInfo.sortedUserNameToCredentialEntryList
    val authenticationEntryList = providerDisplayInfo.authenticationEntryList
    // Show at most 4 entries (credential type or locked type) in this primary page
    val primaryPageCredentialEntryList =
        sortedUserNameToCredentialEntryList.take(MAX_ENTRY_FOR_PRIMARY_PAGE)
    val primaryPageLockedEntryList = authenticationEntryList.take(
        max(0, MAX_ENTRY_FOR_PRIMARY_PAGE - primaryPageCredentialEntryList.size)
    )
    SheetContainerCard {
        val preferTopBrandingContent = requestDisplayInfo.preferTopBrandingContent
        val singleProviderId = findSingleProviderIdForPrimaryPage(
                primaryPageCredentialEntryList,
                primaryPageLockedEntryList
        )
        if (preferTopBrandingContent != null) {
            item {
                HeadlineProviderIconAndName(
                    preferTopBrandingContent.icon,
                    preferTopBrandingContent.displayName
                )
            }
        } else {
            // When only one provider's entries will be displayed on the primary page, display that
            // provider's icon + name up top.
            if (singleProviderId != null) {
                // First should always work but just to be safe.
                val providerInfo = providerInfoList.firstOrNull { it.id == singleProviderId }
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

        val hasSingleEntry = primaryPageCredentialEntryList.size +
                primaryPageLockedEntryList.size == 1
        val areAllPasswordsOnPrimaryScreen = primaryPageLockedEntryList.isEmpty() &&
                primaryPageCredentialEntryList.all {
                    it.sortedCredentialEntryList.first().credentialType == CredentialType.PASSWORD
                }
        val areAllPasskeysOnPrimaryScreen = primaryPageLockedEntryList.isEmpty() &&
                primaryPageCredentialEntryList.all {
                    it.sortedCredentialEntryList.first().credentialType == CredentialType.PASSKEY
                }
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
                            if (areAllPasskeysOnPrimaryScreen)
                                R.string.get_dialog_title_use_passkey_for
                            else if (areAllPasswordsOnPrimaryScreen)
                                R.string.get_dialog_title_use_password_for
                            else if (authenticationEntryList.isNotEmpty())
                                R.string.get_dialog_title_unlock_options_for
                            else R.string.get_dialog_title_use_sign_in_for
                        } else {
                            if (areAllPasswordsOnPrimaryScreen)
                                R.string.get_dialog_title_choose_password_for
                            else if (areAllPasskeysOnPrimaryScreen)
                                R.string.get_dialog_title_choose_passkey_for
                            else
                                R.string.get_dialog_title_choose_sign_in_for
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
                    primaryPageCredentialEntryList.forEach {
                        val entry = it.sortedCredentialEntryList.first()
                        CredentialEntryRow(
                                credentialEntryInfo = entry,
                                onEntrySelected = onEntrySelected,
                                enforceOneLine = true,
                                onTextLayout = {
                                    showMoreForTruncatedEntry.value = it.hasVisualOverflow
                                },
                                hasSingleEntry = hasSingleEntry,
                                hasSingleProvider = singleProviderId != null,
                                shouldOverrideIcon = entry.isDefaultIconPreferredAsSingleProvider &&
                                        (singleProviderId != null),
                                shouldRemoveTypeDisplayName = areAllPasswordsOnPrimaryScreen ||
                                        areAllPasskeysOnPrimaryScreen
                        )
                    }
                    primaryPageLockedEntryList.forEach {
                        AuthenticationEntryRow(
                                authenticationEntryInfo = it,
                                onEntrySelected = onEntrySelected,
                                enforceOneLine = true,
                        )
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

/**
 * Attempt to find a single provider id, if it has supplied all the entries to be displayed on the
 * front page; otherwise if multiple providers are found, return null.
 */
private fun findSingleProviderIdForPrimaryPage(
    primaryPageCredentialEntryList: List<PerUserNameCredentialEntryList>,
    primaryPageLockedEntryList: List<AuthenticationEntryInfo>
): String? {
    var providerId: String? = null
    primaryPageCredentialEntryList.forEach {
        val currProviderId = it.sortedCredentialEntryList.first().providerId
        if (providerId == null) {
            providerId = currProviderId
        } else if (providerId != currProviderId) {
            return null
        }
    }
    primaryPageLockedEntryList.forEach {
        val currProviderId = it.providerId
        if (providerId == null) {
            providerId = currProviderId
        } else if (providerId != currProviderId) {
            return null
        }
    }
    return providerId
}

/**
 * Draws the secondary credential selection page, where all sign-in options are listed.
 *
 * By default, this card has 'back' navigation whereby user can navigate back to invoke
 * [onBackButtonClicked]. However if a different top bar with possibly a different navigation
 * is required, then the caller of this Composable can set a [customTopBar].
 */
@Composable
fun AllSignInOptionCard(
    providerInfoList: List<ProviderInfo>,
    providerDisplayInfo: ProviderDisplayInfo,
    onEntrySelected: (EntryInfo) -> Unit,
    onBackButtonClicked: () -> Unit,
    onCancel: () -> Unit,
    onLog: @Composable (UiEventEnum) -> Unit,
    customTopBar: (@Composable() () -> Unit)? = null
) {
    val sortedUserNameToCredentialEntryList =
        providerDisplayInfo.sortedUserNameToCredentialEntryList
    val authenticationEntryList = providerDisplayInfo.authenticationEntryList
    SheetContainerCard(topAppBar = {
        if (customTopBar != null) {
            customTopBar()
        } else {
            MoreOptionTopAppBar(
                    text = stringResource(R.string.get_dialog_title_sign_in_options),
                    onNavigationIconClicked = onBackButtonClicked,
                    bottomPadding = 0.dp,
                    navigationIcon = Icons.Filled.ArrowBack,
                    navigationIconContentDescription = stringResource(
                            R.string.accessibility_back_arrow_button
            ))
        }
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
    onEntrySelected: (EntryInfo) -> Unit,
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
    onEntrySelected: (EntryInfo) -> Unit,
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
    onEntrySelected: (EntryInfo) -> Unit,
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
    onEntrySelected: (EntryInfo) -> Unit,
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
    onEntrySelected: (EntryInfo) -> Unit,
    enforceOneLine: Boolean = false,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    // Make optional since the secondary page doesn't care about this value.
    hasSingleEntry: Boolean = false,
    // For primary page only, if all display entries come from the same provider AND if that
    // provider has opted in via isDefaultIconPreferredAsSingleProvider, then we override the
    // display icon to the default icon for the given credential type.
    shouldOverrideIcon: Boolean = false,
    // For primary page only, if all entries come from the same provider, then remove that provider
    // name from each entry, since that provider icon + name will be shown front and central at
    // the top of the bottom sheet.
    hasSingleProvider: Boolean = false,
    // For primary page only, if all visible entrise are of the same type and that type is passkey
    // or password, then set this bit to true to remove the type display name from each entry for
    // simplification, since that info is mentioned in the title.
    shouldRemoveTypeDisplayName: Boolean = false,
) {
    val (username, displayName) = if (credentialEntryInfo.credentialType == CredentialType.PASSKEY)
        userAndDisplayNameForPasskey(
            credentialEntryInfo.userName, credentialEntryInfo.displayName ?: "")
    else Pair(credentialEntryInfo.userName, credentialEntryInfo.displayName)

    // For primary page, if
    val overrideIcon: Painter? =
        if (shouldOverrideIcon) {
            when (credentialEntryInfo.credentialType) {
                CredentialType.PASSKEY -> painterResource(R.drawable.ic_passkey_24)
                CredentialType.PASSWORD -> painterResource(R.drawable.ic_password_24)
                else -> painterResource(R.drawable.ic_other_sign_in_24)
            }
        } else null

    Entry(
        onClick = { onEntrySelected(credentialEntryInfo) },
        iconImageBitmap =
        if (overrideIcon == null) credentialEntryInfo.icon?.toBitmap()?.asImageBitmap() else null,
        shouldApplyIconImageBitmapTint = credentialEntryInfo.shouldTintIcon,
        // Fall back to iconPainter if iconImageBitmap isn't available
        iconPainter =
        if (overrideIcon != null) overrideIcon
        else if (credentialEntryInfo.icon == null) painterResource(R.drawable.ic_other_sign_in_24)
        else null,
        entryHeadlineText = username,
        entrySecondLineText = displayName,
        entryThirdLineText =
        (if (hasSingleEntry)
            if (shouldRemoveTypeDisplayName) emptyList()
            // Still show the type display name for all non-password/passkey types since it won't be
            // mentioned in the bottom sheet heading.
            else listOf(credentialEntryInfo.credentialTypeDisplayName)
        else listOf(
                if (shouldRemoveTypeDisplayName) null
                else credentialEntryInfo.credentialTypeDisplayName,
                if (hasSingleProvider) null else credentialEntryInfo.providerDisplayName
        )).filterNot{ it.isNullOrBlank() }.let { itemsToDisplay ->
            if (itemsToDisplay.isEmpty()) null
            else itemsToDisplay.joinToString(
                separator = stringResource(R.string.get_dialog_sign_in_type_username_separator)
            )
        },
        enforceOneLine = enforceOneLine,
        onTextLayout = onTextLayout,
        affiliatedDomainText = credentialEntryInfo.affiliatedDomain,
    )
}

@Composable
fun AuthenticationEntryRow(
    authenticationEntryInfo: AuthenticationEntryInfo,
    onEntrySelected: (EntryInfo) -> Unit,
    enforceOneLine: Boolean = false,
) {
    Entry(
        onClick = if (authenticationEntryInfo.isUnlockedAndEmpty) {
            {}
        } // No-op
        else {
            { onEntrySelected(authenticationEntryInfo) }
        },
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
    onEntrySelected: (EntryInfo) -> Unit,
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
