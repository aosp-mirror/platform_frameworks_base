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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme

@Composable
fun CredentialListSectionHeader(text: String, isFirstSection: Boolean) {
    InternalSectionHeader(
        text = text,
        color = LocalAndroidColorScheme.current.onSurfaceVariant,
        applyTopPadding = !isFirstSection
    )
}

@Composable
fun MoreAboutPasskeySectionHeader(text: String) {
    InternalSectionHeader(text, LocalAndroidColorScheme.current.onSurface)
}

@Composable
private fun InternalSectionHeader(text: String, color: Color, applyTopPadding: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(
        top = if (applyTopPadding) 8.dp else 0.dp
    )) {
        SectionHeaderText(
            text,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            color = color
        )
    }
}