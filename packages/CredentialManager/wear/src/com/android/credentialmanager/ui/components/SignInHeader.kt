/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.common.ui.components.WearTitleText

/* Used as header across Credential Selector screens. */
@Composable
fun SignInHeader(
    icon: Drawable?,
    title: String,
) {

    Row {
        Spacer(Modifier.weight(0.073f)) // 7.3% side margin
        Column(
            modifier = Modifier.weight(0.854f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (icon != null) {
                Icon(
                    bitmap = icon.toBitmap().asImageBitmap(),
                    modifier = Modifier.size(24.dp),
                    // Decorative purpose only.
                    contentDescription = null,
                    tint = Color.Unspecified,
                    )
            }
            Spacer(modifier = Modifier.size(8.dp))

            WearTitleText(
                text = title,
            )

            Spacer(modifier = Modifier.size(8.dp))
        }
        Spacer(Modifier.weight(0.073f)) // 7.3% side margin
    }
}
