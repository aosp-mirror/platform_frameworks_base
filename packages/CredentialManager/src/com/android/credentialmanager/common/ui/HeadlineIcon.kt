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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Tinted primary; centered; 32X32. */
@Composable
fun HeadlineIcon(bitmap: ImageBitmap, tint: Color? = null) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
    ) {
        Icon(
            modifier = Modifier.size(32.dp),
            bitmap = bitmap,
            tint = tint ?: MaterialTheme.colorScheme.primary,
            // Decorative purpose only.
            contentDescription = null,
        )
    }
}

@Composable
fun HeadlineIcon(imageVector: ImageVector) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
    ) {
        Icon(
            modifier = Modifier.size(32.dp),
            imageVector = imageVector,
            tint = MaterialTheme.colorScheme.primary,
            // Decorative purpose only.
            contentDescription = null,
        )
    }
}

@Composable
fun HeadlineIcon(painter: Painter) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
    ) {
        Icon(
            modifier = Modifier.size(32.dp),
            painter = painter,
            tint = MaterialTheme.colorScheme.primary,
            // Decorative purpose only.
            contentDescription = null,
        )
    }
}