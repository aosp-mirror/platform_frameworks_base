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

package com.android.systemui.keyboard.stickykeys.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.systemui.keyboard.stickykeys.shared.model.Locked
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey
import com.android.systemui.keyboard.stickykeys.ui.viewmodel.StickyKeysIndicatorViewModel

@Composable
fun StickyKeysIndicator(viewModel: StickyKeysIndicatorViewModel) {
    val stickyKeys by viewModel.indicatorContent.collectAsState(emptyMap())
    StickyKeysIndicator(stickyKeys)
}

@Composable
fun StickyKeysIndicator(stickyKeys: Map<ModifierKey, Locked>, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            stickyKeys.forEach { (key, isLocked) ->
                key(key) {
                    Text(
                        text = key.text,
                        fontWeight = if (isLocked.locked) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
