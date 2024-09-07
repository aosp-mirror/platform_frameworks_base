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

package com.android.settingslib.spa.widget.scaffold

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.compose.horizontalValues
import com.android.settingslib.spa.framework.compose.verticalValues
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.settingsBackground
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

/**
 * A [Scaffold] which content is can be full screen when needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    ActivityTitle(title)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SettingsTopAppBar(title, scrollBehavior, actions) },
        containerColor = MaterialTheme.colorScheme.settingsBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues.horizontalValues())) {
            content(paddingValues.verticalValues())
        }
    }
}

/**
 * Sets a title for the activity.
 *
 * So the TalkBack can read out the correct page title.
 */
@Composable
internal fun ActivityTitle(title: String) {
    val context = LocalContext.current
    LaunchedEffect(true) {
        context.getActivity()?.title = title
    }
}

private fun Context.getActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

@Preview
@Composable
private fun SettingsScaffoldPreview() {
    SettingsTheme {
        SettingsScaffold(title = "Display") { paddingValues ->
            Column(Modifier.padding(paddingValues)) {
                Preference(object : PreferenceModel {
                    override val title = "Item 1"
                })
                Preference(object : PreferenceModel {
                    override val title = "Item 2"
                })
            }
        }
    }
}
