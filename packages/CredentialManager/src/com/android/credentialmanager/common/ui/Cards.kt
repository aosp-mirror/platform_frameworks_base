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

package com.android.credentialmanager.common.ui

import android.credentials.flags.Flags
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.credentialmanager.ui.theme.Shapes

/**
 * Container card for the whole sheet.
 *
 * Surface 1 color. No vertical padding. 24dp horizontal padding. 24dp bottom padding. 24dp top
 * padding if [topAppBar] is not present, and none otherwise.
 */
@Composable
fun SheetContainerCard(
    topAppBar: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentVerticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
        border = null,
        colors = CardDefaults.cardColors(
            containerColor = LocalAndroidColorScheme.current.surfaceBright,
        ),
    ) {
        if (topAppBar != null) {
            topAppBar()
        }
        LazyColumn(
            modifier = Modifier.padding(
                start = 24.dp,
                end = 24.dp,
                bottom = if (Flags.selectorUiImprovementsEnabled()) 8.dp else 18.dp,
                top = if (topAppBar == null) 24.dp else 0.dp
            ).fillMaxWidth().wrapContentHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
            verticalArrangement = contentVerticalArrangement,
            // The bottom sheet overlaps with the navigation bars but make sure the actual content
            // in the bottom sheet does not.
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        )
    }
}

/**
 * Container card for the entries.
 *
 * Surface 3 color. No padding. Four rounded corner shape.
 */
@Composable
fun CredentialContainerCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
        shape = Shapes.medium,
        border = null,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
        ),
        content = content,
    )
}