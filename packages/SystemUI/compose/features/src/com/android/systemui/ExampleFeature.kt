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

package com.android.systemui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * This is an example Compose feature, which shows a text and a count that is incremented when
 * clicked. We also show the max width available to this component, which is displayed either next
 * to or below the text depending on that max width.
 */
@Composable
fun ExampleFeature(text: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val maxWidth = maxWidth
        if (maxWidth < 600.dp) {
            Column {
                CounterTile(text)
                Spacer(Modifier.size(16.dp))
                MaxWidthTile(maxWidth)
            }
        } else {
            Row {
                CounterTile(text)
                Spacer(Modifier.size(16.dp))
                MaxWidthTile(maxWidth)
            }
        }
    }
}

@Composable
private fun CounterTile(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(28.dp),
    ) {
        var count by remember { mutableStateOf(0) }
        Column(
            Modifier.clickable { count++ }.padding(16.dp),
        ) {
            Text(text)
            Text("I was clicked $count times.")
        }
    }
}

@Composable
private fun MaxWidthTile(maxWidth: Dp, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(28.dp),
    ) {
        Text(
            "The max available width to me is: ${maxWidth.value.roundToInt()}dp",
            Modifier.padding(16.dp)
        )
    }
}
