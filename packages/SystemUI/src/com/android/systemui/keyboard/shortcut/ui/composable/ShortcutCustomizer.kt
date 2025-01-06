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

package com.android.systemui.keyboard.shortcut.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import com.android.systemui.res.R

@Composable
fun ShortcutCustomizationDialog(
    uiState: ShortcutCustomizationUiState,
    modifier: Modifier = Modifier,
    onKeyPress: (KeyEvent) -> Boolean,
    onCancel: () -> Unit,
    onConfirmSetShortcut: () -> Unit,
    onConfirmDeleteShortcut: () -> Unit,
    onConfirmResetShortcut: () -> Unit,
) {
    when (uiState) {
        is ShortcutCustomizationUiState.AddShortcutDialog -> {
            AddShortcutDialog(modifier, uiState, onKeyPress, onCancel, onConfirmSetShortcut)
        }
        is ShortcutCustomizationUiState.DeleteShortcutDialog -> {
            DeleteShortcutDialog(modifier, onCancel, onConfirmDeleteShortcut)
        }
        is ShortcutCustomizationUiState.ResetShortcutDialog -> {
            ResetShortcutDialog(modifier, onCancel, onConfirmResetShortcut)
        }
        else -> {
            /* No-op */
        }
    }
}

@Composable
private fun AddShortcutDialog(
    modifier: Modifier,
    uiState: ShortcutCustomizationUiState.AddShortcutDialog,
    onKeyPress: (KeyEvent) -> Boolean,
    onCancel: () -> Unit,
    onConfirmSetShortcut: () -> Unit
){
    Column(modifier = modifier) {
        Title(uiState.shortcutLabel)
        Description(
            text =
            stringResource(
                id = R.string.shortcut_customize_mode_add_shortcut_description
            )
        )
        PromptShortcutModifier(
            modifier =
            Modifier.padding(top = 24.dp, start = 116.5.dp, end = 116.5.dp)
                .width(131.dp)
                .height(48.dp),
            defaultModifierKey = uiState.defaultCustomShortcutModifierKey,
        )
        SelectedKeyCombinationContainer(
            shouldShowError = uiState.errorMessage.isNotEmpty(),
            onKeyPress = onKeyPress,
            pressedKeys = uiState.pressedKeys,
        )
        ErrorMessageContainer(uiState.errorMessage)
        DialogButtons(
            onCancel,
            isConfirmButtonEnabled = uiState.pressedKeys.isNotEmpty(),
            onConfirm = onConfirmSetShortcut,
            confirmButtonText =
            stringResource(
                R.string.shortcut_helper_customize_dialog_set_shortcut_button_label
            ),
        )
    }
}

@Composable
private fun DeleteShortcutDialog(
    modifier: Modifier,
    onCancel: () -> Unit,
    onConfirmDeleteShortcut: () -> Unit
){
    ConfirmationDialog(
        modifier = modifier,
        title =
        stringResource(
            id = R.string.shortcut_customize_mode_remove_shortcut_dialog_title
        ),
        description =
        stringResource(
            id = R.string.shortcut_customize_mode_remove_shortcut_description
        ),
        confirmButtonText =
        stringResource(R.string.shortcut_helper_customize_dialog_remove_button_label),
        onCancel = onCancel,
        onConfirm = onConfirmDeleteShortcut,
    )
}

@Composable
private fun ResetShortcutDialog(
    modifier: Modifier,
    onCancel: () -> Unit,
    onConfirmResetShortcut: () -> Unit
){
    ConfirmationDialog(
        modifier = modifier,
        title =
        stringResource(
            id = R.string.shortcut_customize_mode_reset_shortcut_dialog_title
        ),
        description =
        stringResource(
            id = R.string.shortcut_customize_mode_reset_shortcut_description
        ),
        confirmButtonText =
        stringResource(R.string.shortcut_helper_customize_dialog_reset_button_label),
        onCancel = onCancel,
        onConfirm = onConfirmResetShortcut,
    )
}

@Composable
private fun ConfirmationDialog(
    modifier: Modifier,
    title: String,
    description: String,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier) {
        Title(title = title)
        Description(text = description)
        DialogButtons(
            onCancel = onCancel,
            onConfirm = onConfirm,
            confirmButtonText = confirmButtonText,
        )
    }
}

@Composable
private fun DialogButtons(
    onCancel: () -> Unit,
    isConfirmButtonEnabled: Boolean = true,
    onConfirm: () -> Unit,
    confirmButtonText: String,
) {
    Row(
        modifier =
            Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp)
                .sizeIn(minWidth = 316.dp, minHeight = 48.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.End,
    ) {
        ShortcutHelperButton(
            shape = RoundedCornerShape(50.dp),
            onClick = onCancel,
            color = Color.Transparent,
            width = 80.dp,
            contentColor = MaterialTheme.colorScheme.primary,
            text = stringResource(R.string.shortcut_helper_customize_dialog_cancel_button_label),
        )
        Spacer(modifier = Modifier.width(8.dp))
        ShortcutHelperButton(
            onClick = onConfirm,
            color = MaterialTheme.colorScheme.primary,
            width = 116.dp,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            text = confirmButtonText,
            enabled = isConfirmButtonEnabled,
        )
    }
}

@Composable
private fun ErrorMessageContainer(errorMessage: String) {
    if (errorMessage.isNotEmpty()) {
        Box(modifier = Modifier.padding(horizontal = 16.dp).width(332.dp).height(40.dp)) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.W500,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 24.dp).width(252.dp),
            )
        }
    }
}

@Composable
private fun SelectedKeyCombinationContainer(
    shouldShowError: Boolean,
    onKeyPress: (KeyEvent) -> Boolean,
    pressedKeys: List<ShortcutKey>,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val outlineColor =
        if (!isFocused) MaterialTheme.colorScheme.outline
        else if (shouldShowError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ClickableShortcutSurface(
        onClick = {},
        color = Color.Transparent,
        shape = RoundedCornerShape(50.dp),
        modifier =
            Modifier.padding(all = 16.dp)
                .sizeIn(minWidth = 332.dp, minHeight = 56.dp)
                .border(width = 2.dp, color = outlineColor, shape = RoundedCornerShape(50.dp))
                .onKeyEvent { onKeyPress(it) }
                .focusRequester(focusRequester),
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pressedKeys.isEmpty()) {
                PressKeyPrompt()
            } else {
                PressedKeysTextContainer(pressedKeys)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (shouldShowError) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RowScope.PressedKeysTextContainer(pressedKeys: List<ShortcutKey>) {
    pressedKeys.forEachIndexed { keyIndex, key ->
        if (keyIndex > 0) {
            ShortcutKeySeparator()
        }
        if (key is ShortcutKey.Text) {
            ShortcutTextKey(key)
        } else if (key is ShortcutKey.Icon) {
            ShortcutIconKey(key)
        }
    }
}

@Composable
private fun ShortcutKeySeparator() {
    Text(
        text = stringResource(id = R.string.shortcut_helper_plus_symbol),
        style = MaterialTheme.typography.titleSmall,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowScope.ShortcutIconKey(key: ShortcutKey.Icon) {
    Icon(
        painter =
            when (key) {
                is ShortcutKey.Icon.ResIdIcon -> painterResource(key.drawableResId)
                is ShortcutKey.Icon.DrawableIcon -> rememberDrawablePainter(drawable = key.drawable)
            },
        contentDescription = null,
        modifier = Modifier.align(Alignment.CenterVertically).height(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PressKeyPrompt() {
    Text(
        text = stringResource(id = R.string.shortcut_helper_add_shortcut_dialog_placeholder),
        style = MaterialTheme.typography.titleSmall,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ShortcutTextKey(key: ShortcutKey.Text) {
    Text(
        text = key.value,
        style = MaterialTheme.typography.titleSmall,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Title(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontSize = 24.sp,
        modifier =
            Modifier.padding(horizontal = 24.dp).width(316.dp).wrapContentSize(Alignment.Center),
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = 32.sp,
        fontWeight = FontWeight.W400,
    )
}

@Composable
private fun Description(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp)
                .width(316.dp)
                .wrapContentSize(Alignment.Center),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PromptShortcutModifier(
    modifier: Modifier,
    defaultModifierKey: ShortcutKey.Icon.ResIdIcon,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ActionKeyContainer(defaultModifierKey)
        PlusIconContainer()
    }
}

@Composable
private fun ActionKeyContainer(defaultModifierKey: ShortcutKey.Icon.ResIdIcon) {
    Row(
        modifier =
            Modifier.height(48.dp)
                .width(105.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(all = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionKeyIcon(defaultModifierKey)
        ActionKeyText()
    }
}

@Composable
private fun ActionKeyText() {
    Text(
        text = "Action",
        style = MaterialTheme.typography.titleMedium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        modifier = Modifier.wrapContentSize(Alignment.Center),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ActionKeyIcon(defaultModifierKey: ShortcutKey.Icon.ResIdIcon) {
    Icon(
        painter = painterResource(id = defaultModifierKey.drawableResId),
        contentDescription = stringResource(R.string.shortcut_helper_content_description_meta_key),
        modifier = Modifier.size(24.dp).wrapContentSize(Alignment.Center),
    )
}

@Composable
private fun PlusIconContainer() {
    Icon(
        tint = MaterialTheme.colorScheme.onSurface,
        imageVector = Icons.Default.Add,
        contentDescription =
            stringResource(id = R.string.shortcut_helper_content_description_plus_icon),
        modifier = Modifier.padding(vertical = 12.dp).size(24.dp).wrapContentSize(Alignment.Center),
    )
}
