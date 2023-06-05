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

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.systemui.bouncer.ui.composable

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.R
import com.android.systemui.bouncer.ui.viewmodel.AuthMethodBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PasswordBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PatternBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The bouncer scene displays authentication challenges like PIN, password, or pattern. */
class BouncerScene(
    private val viewModel: BouncerViewModel,
    private val dialogFactory: () -> AlertDialog,
) : ComposableScene {
    override val key = SceneKey.Bouncer

    override fun destinationScenes(
        containerName: String,
    ): StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow<Map<UserAction, SceneModel>>(
                mapOf(
                    UserAction.Back to SceneModel(SceneKey.Lockscreen),
                )
            )
            .asStateFlow()

    @Composable
    override fun Content(
        containerName: String,
        modifier: Modifier,
    ) = BouncerScene(viewModel, dialogFactory, modifier)
}

@Composable
private fun BouncerScene(
    viewModel: BouncerViewModel,
    dialogFactory: () -> AlertDialog,
    modifier: Modifier = Modifier,
) {
    val message: BouncerViewModel.MessageViewModel by viewModel.message.collectAsState()
    val authMethodViewModel: AuthMethodBouncerViewModel? by viewModel.authMethod.collectAsState()
    val dialogMessage: String? by viewModel.throttlingDialogMessage.collectAsState()
    var dialog: Dialog? by remember { mutableStateOf(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(60.dp),
        modifier =
            modifier.background(MaterialTheme.colorScheme.surface).fillMaxSize().padding(32.dp)
    ) {
        Crossfade(
            targetState = message,
            label = "Bouncer message",
            animationSpec = if (message.isUpdateAnimated) tween() else snap(),
        ) { message ->
            Text(
                text = message.text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Box(Modifier.weight(1f)) {
            when (val nonNullViewModel = authMethodViewModel) {
                is PinBouncerViewModel ->
                    PinBouncer(
                        viewModel = nonNullViewModel,
                        modifier = Modifier.align(Alignment.Center),
                    )
                is PasswordBouncerViewModel ->
                    PasswordBouncer(
                        viewModel = nonNullViewModel,
                        modifier = Modifier.align(Alignment.Center),
                    )
                is PatternBouncerViewModel ->
                    PatternBouncer(
                        viewModel = nonNullViewModel,
                        modifier =
                            Modifier.aspectRatio(1f, matchHeightConstraintsFirst = false)
                                .align(Alignment.BottomCenter),
                    )
                else -> Unit
            }
        }

        Button(
            onClick = viewModel::onEmergencyServicesButtonClicked,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
        ) {
            Text(
                text = stringResource(com.android.internal.R.string.lockscreen_emergency_call),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (dialogMessage != null) {
            if (dialog == null) {
                dialog =
                    dialogFactory().apply {
                        setMessage(dialogMessage)
                        setButton(
                            DialogInterface.BUTTON_NEUTRAL,
                            context.getString(R.string.ok),
                        ) { _, _ ->
                            viewModel.onThrottlingDialogDismissed()
                        }
                        setCancelable(false)
                        setCanceledOnTouchOutside(false)
                        show()
                    }
            }
        } else {
            dialog?.dismiss()
            dialog = null
        }
    }
}
