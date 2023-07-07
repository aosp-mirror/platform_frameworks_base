/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.widget.preference

import com.android.settingslib.spa.framework.util.EntryHighlight
import androidx.compose.material3.IconButton
import androidx.compose.runtime.State
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon

@Composable
fun TwoTargetButtonPreference(
        title: String,
        summary: State<String>,
        icon: @Composable (() -> Unit)? = null,
        onClick: () -> Unit,
        buttonIcon: ImageVector,
        buttonIconDescription: String,
        onButtonClick: () -> Unit
) {
    EntryHighlight {
        TwoTargetPreference(
                title = title,
                summary = summary,
                onClick = onClick,
                icon = icon) {
            IconButton(onClick = onButtonClick) {
                Icon(imageVector = buttonIcon, contentDescription = buttonIconDescription)
            }
        }
    }
}