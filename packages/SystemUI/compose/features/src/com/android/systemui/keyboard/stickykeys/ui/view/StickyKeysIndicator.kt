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

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.keyboard.stickykeys.shared.model.Locked
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey
import com.android.systemui.keyboard.stickykeys.ui.viewmodel.StickyKeysIndicatorViewModel

fun createStickyKeyIndicatorView(context: Context, viewModel: StickyKeysIndicatorViewModel): View {
    return ComposeView(context).apply {
        setContent {
            PlatformTheme {
                val defaultContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                CompositionLocalProvider(LocalContentColor provides defaultContentColor) {
                    StickyKeysIndicator(viewModel)
                }
            }
        }
    }
}

@Composable
fun StickyKeysIndicator(viewModel: StickyKeysIndicatorViewModel) {
    val stickyKeys by viewModel.indicatorContent.collectAsStateWithLifecycle(emptyMap())
    StickyKeysIndicator(stickyKeys)
}

@Composable
fun StickyKeysIndicator(stickyKeys: Map<ModifierKey, Locked>, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.heightIn(min = 84.dp).width(96.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            stickyKeys.forEach { (key, isLocked) -> key(key) { StickyKeyText(key, isLocked) } }
        }
    }
}

@Composable
private fun StickyKeyText(key: ModifierKey, isLocked: Locked, modifier: Modifier = Modifier) {
    Text(
        text = key.displayedText,
        fontWeight = if (isLocked.locked) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (isLocked.locked) {
                MaterialTheme.colorScheme.inverseOnSurface
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        modifier = modifier
    )
}
