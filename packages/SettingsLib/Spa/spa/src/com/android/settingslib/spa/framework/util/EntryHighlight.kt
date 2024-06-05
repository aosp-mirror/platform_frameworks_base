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

package com.android.settingslib.spa.framework.util

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.settingslib.spa.framework.common.LocalEntryDataProvider

@Composable
internal fun EntryHighlight(content: @Composable () -> Unit) {
    val entryData = LocalEntryDataProvider.current
    val entryIsHighlighted = rememberSaveable { entryData.isHighlighted }
    var localHighlighted by rememberSaveable { mutableStateOf(false) }
    SideEffect {
        localHighlighted = entryIsHighlighted
    }

    val backgroundColor by animateColorAsState(
        targetValue = when {
            localHighlighted -> MaterialTheme.colorScheme.surfaceVariant
            else -> Color.Transparent
        },
        animationSpec = repeatable(
            iterations = 3,
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Restart
        ),
        label = "BackgroundColorAnimation",
    )
    Box(modifier = Modifier.background(color = backgroundColor)) {
        content()
    }
}
