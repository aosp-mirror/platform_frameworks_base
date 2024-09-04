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

@file:OptIn(ExperimentalHorologistApi::class)

package com.android.credentialmanager.ui.screens.single

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScalingLazyColumnState

@Composable
fun SingleAccountScreen(
    headerContent: @Composable () -> Unit,
    accountContent: @Composable () -> Unit,
    columnState: ScalingLazyColumnState,
    content: ScalingLazyListScope.() -> Unit,
) {
    Row {
        Spacer(Modifier.weight(0.052f)) // 5.2% side margin
        ScalingLazyColumn(
            columnState = columnState,
            modifier = Modifier.weight(0.896f).fillMaxSize(),
        ) {
            item { headerContent() }
            item { accountContent() }
            content()
        }
        Spacer(Modifier.weight(0.052f)) // 5.2% side margin
    }
}