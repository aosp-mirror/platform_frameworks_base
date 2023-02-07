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

package com.android.credentialmanager.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.credentialmanager.common.material.ModalBottomSheetLayout
import com.android.credentialmanager.common.material.ModalBottomSheetValue
import com.android.credentialmanager.common.material.rememberModalBottomSheetState
import com.android.credentialmanager.ui.theme.EntryShape

/** Draws a modal bottom sheet with the same styles and effects shared by various flows. */
@Composable
fun ModalBottomSheet(
    sheetContent: @Composable ColumnScope.() -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(
        initialValue = ModalBottomSheetValue.Expanded,
        skipHalfExpanded = true
    )
    ModalBottomSheetLayout(
        sheetBackgroundColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.background(Color.Transparent),
        sheetState = state,
        sheetContent = sheetContent,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f),
        sheetShape = EntryShape.TopRoundedCorner,
    ) {}
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == ModalBottomSheetValue.Hidden) {
            onDismiss()
        }
    }
}