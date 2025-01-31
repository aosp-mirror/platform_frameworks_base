/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.ui.compose.section

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChargingStation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.communal.ui.viewmodel.HubOnboardingViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.createBottomSheet
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

class HubOnboardingSection
@Inject
constructor(
    private val viewModelFactory: HubOnboardingViewModel.Factory,
    private val dialogFactory: SystemUIDialogFactory,
) {
    @Composable
    fun BottomSheet() {
        val viewModel = rememberViewModel("HubOnboardingSection") { viewModelFactory.create() }
        val shouldShowHubOnboarding by
            viewModel.shouldShowHubOnboarding.collectAsStateWithLifecycle(false)

        if (!shouldShowHubOnboarding) {
            return
        }

        var show by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            delay(SHOW_BOTTOMSHEET_DELAY_MS)
            show = true
        }

        if (show) {
            HubOnboardingBottomSheet(shouldShowBottomSheet = true, dialogFactory = dialogFactory) {
                viewModel.onDismissed()
            }
        }
    }

    companion object {
        val SHOW_BOTTOMSHEET_DELAY_MS = 1000.milliseconds
    }
}

@Composable
private fun HubOnboardingBottomSheet(
    shouldShowBottomSheet: Boolean,
    dialogFactory: SystemUIDialogFactory,
    onDismiss: () -> Unit,
) {
    var dialog: ComponentSystemUIDialog? by remember { mutableStateOf(null) }
    var dismissingDueToCancel by remember { mutableStateOf(false) }

    DisposableEffect(shouldShowBottomSheet) {
        if (shouldShowBottomSheet) {
            dialog =
                dialogFactory
                    .createBottomSheet(
                        content = { HubOnboardingBottomSheetContent { dialog?.dismiss() } },
                        isDraggable = true,
                        maxWidth = 627.dp,
                    )
                    .apply {
                        setOnDismissListener {
                            // Don't set the onboarding dismissed flag if the dismiss was due to a
                            // cancel. Note that a "dismiss" is something initiated by the user
                            // (e.g. swipe down or tapping outside), while a "cancel" is a dismiss
                            // not initiated by the user (e.g. timing out to dream). We only want
                            // to mark the bottom sheet as dismissed if the user explicitly
                            // dismissed it.
                            if (!dismissingDueToCancel) {
                                onDismiss()
                            }
                        }
                        setOnCancelListener { dismissingDueToCancel = true }
                        show()
                    }
        }

        onDispose {
            dialog?.cancel()
            dialog = null
        }
    }
}

@Composable
private fun HubOnboardingBottomSheetContent(onButtonClicked: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ChargingStation,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.hub_onboarding_bottom_sheet_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(32.dp))
        // TODO(b/388283881): Replace with correct animations and possibly add a content description
        // if necessary.
        Image(painter = painterResource(R.drawable.hub_onboarding_bg), contentDescription = null)
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            modifier = Modifier.width(300.dp),
            text = stringResource(R.string.hub_onboarding_bottom_sheet_text),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            modifier = Modifier.align(Alignment.End),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            onClick = onButtonClicked,
        ) {
            Text(
                stringResource(R.string.hub_onboarding_bottom_sheet_action_button),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
