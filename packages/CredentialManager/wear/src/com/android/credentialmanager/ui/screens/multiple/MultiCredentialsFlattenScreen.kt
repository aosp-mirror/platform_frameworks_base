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

import android.graphics.drawable.Drawable
import com.android.credentialmanager.ui.screens.UiState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.android.credentialmanager.ui.components.SignInHeader
import com.android.credentialmanager.CredentialSelectorUiState.Get.MultipleEntry
import com.android.credentialmanager.R
import com.android.credentialmanager.activity.StartBalIntentSenderForResultContract
import com.android.credentialmanager.model.get.ActionEntryInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.ui.components.CredentialsScreenChip
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScalingLazyColumnState


/**
 * Screen that shows multiple credentials to select from, grouped by accounts
 *
 * @param credentialSelectorUiState The app bar view model.
 * @param screenIcon The view model corresponding to the home page.
 * @param columnState ScalingLazyColumn configuration to be be applied
 * @param modifier styling for composable
 * @param viewModel ViewModel that updates ui state for this screen
 * @param navController handles navigation events from this screen
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun MultiCredentialsFlattenScreen(
    credentialSelectorUiState: MultipleEntry,
    screenIcon: Drawable?,
    columnState: ScalingLazyColumnState,
    modifier: Modifier = Modifier,
    viewModel: MultiCredentialsFlattenViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        UiState.CredentialScreen -> {
            MultiCredentialsFlattenScreen(
                state = credentialSelectorUiState,
                columnState = columnState,
                screenIcon = screenIcon,
                onActionEntryClicked = viewModel::onActionEntryClicked,
                onCredentialClicked = viewModel::onCredentialClicked,
                modifier = modifier,
            )
        }

        is UiState.CredentialSelected -> {
            val launcher = rememberLauncherForActivityResult(
                StartBalIntentSenderForResultContract()
            ) {
                viewModel.onInfoRetrieved(it.resultCode, null)
            }

            SideEffect {
                state.intentSenderRequest?.let {
                    launcher.launch(it)
                }
            }
        }

        UiState.Cancel -> {
            navController.popBackStack()
        }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun MultiCredentialsFlattenScreen(
    state: MultipleEntry,
    columnState: ScalingLazyColumnState,
    screenIcon: Drawable?,
    onActionEntryClicked: (entryInfo: ActionEntryInfo) -> Unit,
    onCredentialClicked: (entryInfo: CredentialEntryInfo) -> Unit,
    modifier: Modifier,
) {
    ScalingLazyColumn(
        columnState = columnState,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            // make this credential specific if all credentials are same
            SignInHeader(
                icon = screenIcon,
                title = stringResource(R.string.sign_in_options_title),
            )
        }

        state.accounts.forEach { userNameEntries ->
            item {
                Text(
                    text = userNameEntries.userName,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.title3
                )
            }

            userNameEntries.sortedCredentialEntryList.forEach { credential: CredentialEntryInfo ->
                item {
                    CredentialsScreenChip(
                        label = credential.userName,
                        onClick = { onCredentialClicked(credential) },
                        secondaryLabel = credential.userName,
                        icon = credential.icon,
                        modifier = modifier,
                    )
                }
            }
        }
        item {
            Text(
                text = "Manage Sign-ins",
                modifier = Modifier
                    .padding(top = 6.dp)
                    .padding(horizontal = 10.dp),
                style = MaterialTheme.typography.title3
            )
        }

        state.actionEntryList.forEach {
            item {
                    CredentialsScreenChip(
                        label = it.title,
                        onClick = { onActionEntryClicked(it) },
                        secondaryLabel = null,
                        icon = it.icon,
                        modifier = modifier,
                    )
            }
        }
    }
}


