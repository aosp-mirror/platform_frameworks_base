/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalHorologistApi::class)

package com.android.credentialmanager.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import com.android.credentialmanager.CredentialSelectorUiState
import com.android.credentialmanager.CredentialSelectorUiState.Get.SingleEntryPerAccount
import com.android.credentialmanager.CredentialSelectorUiState.Get.SingleEntry
import com.android.credentialmanager.CredentialSelectorUiState.Get.MultipleEntry
import com.android.credentialmanager.CredentialSelectorViewModel
import com.android.credentialmanager.FlowEngine
import com.android.credentialmanager.TAG
import com.android.credentialmanager.ui.screens.LoadingScreen
import com.android.credentialmanager.ui.screens.single.passkey.SinglePasskeyScreen
import com.android.credentialmanager.ui.screens.single.password.SinglePasswordScreen
import com.android.credentialmanager.ui.screens.single.signInWithProvider.SignInWithProviderScreen
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.navscaffold.WearNavScaffold
import com.google.android.horologist.compose.navscaffold.composable
import com.google.android.horologist.compose.navscaffold.scrollable
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.ui.screens.multiple.MultiCredentialsFoldScreen
import com.android.credentialmanager.ui.screens.multiple.MultiCredentialsFlattenScreen


@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearApp(
    viewModel: CredentialSelectorViewModel,
    flowEngine: FlowEngine = viewModel,
    onCloseApp: () -> Unit,
) {
    val navController = rememberSwipeDismissableNavController()
    val swipeToDismissBoxState = rememberSwipeToDismissBoxState()
    val navHostState =
        rememberSwipeDismissableNavHostState(swipeToDismissBoxState = swipeToDismissBoxState)
    val selectEntry = flowEngine.getEntrySelector()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WearNavScaffold(
        startDestination = Screen.Loading.route,
        navController = navController,
        state = navHostState,
    ) {
        composable(Screen.Loading.route) {
            LoadingScreen()
        }
        scrollable(Screen.SinglePasswordScreen.route) {
            SinglePasswordScreen(
                entry = (remember { uiState } as SingleEntry).entry,
                columnState = it.columnState,
                flowEngine = flowEngine,
            )
        }

        scrollable(Screen.SinglePasskeyScreen.route) {
            SinglePasskeyScreen(
                entry = (remember { uiState } as SingleEntry).entry,
                columnState = it.columnState,
                flowEngine = flowEngine,
            )
        }

        scrollable(Screen.SignInWithProviderScreen.route) {
            SignInWithProviderScreen(
                entry = (remember { uiState } as SingleEntry).entry,
                columnState = it.columnState,
                flowEngine = flowEngine,
            )
        }

        scrollable(Screen.MultipleCredentialsScreenFold.route) {
            MultiCredentialsFoldScreen(
                credentialSelectorUiState = (remember { uiState } as SingleEntryPerAccount),
                columnState = it.columnState,
                flowEngine = flowEngine,
            )
        }

        scrollable(Screen.MultipleCredentialsScreenFlatten.route) {
            MultiCredentialsFlattenScreen(
                credentialSelectorUiState = (remember { uiState } as MultipleEntry),
                columnState = it.columnState,
                flowEngine = flowEngine,
            )
        }
    }
        BackHandler(true) {
            viewModel.back()
        }
        Log.d(TAG, "uiState change, state: $uiState")
        when (val state = uiState) {
            CredentialSelectorUiState.Idle -> {
                if (navController.currentDestination?.route != Screen.Loading.route) {
                    navController.navigateToLoading()
                }
            }

            is CredentialSelectorUiState.Get -> {
                handleGetNavigation(
                    navController = navController,
                    state = state,
                    onCloseApp = onCloseApp,
                    selectEntry = selectEntry
                )
            }

            CredentialSelectorUiState.Create -> {
                // TODO: b/301206624 - Implement create flow
                onCloseApp()
            }

            is CredentialSelectorUiState.Cancel -> {
                onCloseApp()
            }

            CredentialSelectorUiState.Close -> {
                onCloseApp()
            }
        }
    }

private fun handleGetNavigation(
    navController: NavController,
    state: CredentialSelectorUiState.Get,
    onCloseApp: () -> Unit,
    selectEntry: (entry: EntryInfo, isAutoSelected: Boolean) -> Unit,
) {
    when (state) {
        is SingleEntry -> {
            if (state.entry.isAutoSelectable) {
                selectEntry(state.entry, true)
                return
            }
            when (state.entry.credentialType) {
                CredentialType.UNKNOWN -> {
                    navController.navigateToSignInWithProviderScreen()
                }
                CredentialType.PASSKEY -> {
                    navController.navigateToSinglePasskeyScreen()
                }
                CredentialType.PASSWORD -> {
                    navController.navigateToSinglePasswordScreen()
                }
            }
        }

            is SingleEntryPerAccount -> {
                navController.navigateToMultipleCredentialsFoldScreen()
            }

            is MultipleEntry -> {
                navController.navigateToMultipleCredentialsFlattenScreen()
            }
        }
    }
