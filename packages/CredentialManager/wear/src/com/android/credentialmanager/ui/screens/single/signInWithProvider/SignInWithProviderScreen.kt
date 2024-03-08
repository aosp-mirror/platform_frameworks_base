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

package com.android.credentialmanager.ui.screens.single.signInWithProvider

import android.util.Log
import com.android.credentialmanager.TAG
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.credentialmanager.FlowEngine
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.activity.StartBalIntentSenderForResultContract
import com.android.credentialmanager.ktx.getIntentSenderRequest
import com.android.credentialmanager.ui.components.AccountRow
import com.android.credentialmanager.ui.components.ContinueChip
import com.android.credentialmanager.ui.components.DismissChip
import com.android.credentialmanager.ui.components.SignInHeader
import com.android.credentialmanager.ui.components.SignInOptionsChip
import com.android.credentialmanager.ui.screens.single.SingleAccountScreen
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumnState

/**
 * Screen that shows sign in with provider credential.
 *
 * @param entry The password entry.
 * @param columnState ScalingLazyColumn configuration to be be applied to SingleAccountScreen
 * @param modifier styling for composable
 * @param flowEngine [FlowEngine] that updates ui state for this screen
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun SignInWithProviderScreen(
    entry: CredentialEntryInfo,
    columnState: ScalingLazyColumnState,
    modifier: Modifier = Modifier,
    flowEngine: FlowEngine,
) {
    val launcher = rememberLauncherForActivityResult(
        StartBalIntentSenderForResultContract()
    ) {
        flowEngine.sendSelectionResult(entry, it.resultCode, it.data)
    }
    SingleAccountScreen(
        headerContent = {
            SignInHeader(
                icon = entry.icon,
                title = entry.providerDisplayName,
            )
        },
        accountContent = {
            val displayName = entry.displayName
            if (displayName != null) {
                AccountRow(
                    primaryText = displayName,
                    secondaryText = entry.userName,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                AccountRow(
                    primaryText = entry.userName,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        columnState = columnState,
        modifier = modifier.padding(horizontal = 10.dp)
    ) {
        item {
            Column {
                ContinueChip {
                    entry.getIntentSenderRequest()?.let {
                        launcher.launch(it)
                    } ?: Log.w(TAG, "Cannot parse IntentSenderRequest")
                }
                SignInOptionsChip{ flowEngine.openSecondaryScreen() }
                DismissChip { flowEngine.cancel() }
            }
        }
    }
}