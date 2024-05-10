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

package com.android.systemui.bouncer.ui.composable

import android.view.ViewTreeObserver
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import com.android.systemui.bouncer.ui.viewmodel.PasswordBouncerViewModel

/** UI for the input part of a password-requiring version of the bouncer. */
@Composable
internal fun PasswordBouncer(
    viewModel: PasswordBouncerViewModel,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val isTextFieldFocusRequested by viewModel.isTextFieldFocusRequested.collectAsState()
    LaunchedEffect(isTextFieldFocusRequested) {
        if (isTextFieldFocusRequested) {
            focusRequester.requestFocus()
        }
    }

    val password: String by viewModel.password.collectAsState()
    val isInputEnabled: Boolean by viewModel.isInputEnabled.collectAsState()
    val animateFailure: Boolean by viewModel.animateFailure.collectAsState()

    val isImeVisible by isSoftwareKeyboardVisible()
    LaunchedEffect(isImeVisible) { viewModel.onImeVisibilityChanged(isImeVisible) }

    DisposableEffect(Unit) {
        viewModel.onShown()
        onDispose { viewModel.onHidden() }
    }

    LaunchedEffect(animateFailure) {
        if (animateFailure) {
            // We don't currently have a failure animation for password, just consume it:
            viewModel.onFailureAnimationShown()
        }
    }

    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val lineWidthPx = with(LocalDensity.current) { 2.dp.toPx() }

    TextField(
        value = password,
        onValueChange = viewModel::onPasswordInputChanged,
        enabled = isInputEnabled,
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        keyboardActions =
            KeyboardActions(
                onDone = { viewModel.onAuthenticateKeyPressed() },
            ),
        modifier =
            modifier
                .focusRequester(focusRequester)
                .onFocusChanged { viewModel.onTextFieldFocusChanged(it.isFocused) }
                .drawBehind {
                    drawLine(
                        color = color,
                        start = Offset(x = 0f, y = size.height - lineWidthPx),
                        end = Offset(size.width, y = size.height - lineWidthPx),
                        strokeWidth = lineWidthPx,
                    )
                },
    )
}

/** Returns a [State] with `true` when the IME/keyboard is visible and `false` when it's not. */
@Composable
fun isSoftwareKeyboardVisible(): State<Boolean> {
    val view = LocalView.current
    val viewTreeObserver = view.viewTreeObserver

    return produceState(
        initialValue = false,
        key1 = viewTreeObserver,
    ) {
        val listener =
            ViewTreeObserver.OnGlobalLayoutListener {
                value = view.rootWindowInsets?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
            }

        viewTreeObserver.addOnGlobalLayoutListener(listener)

        awaitDispose { viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
}
