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
 *
 */

package com.android.systemui.common.ui.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.shared.model.Text.Companion.loadText

/** Returns the loaded [String] or `null` if there isn't one. */
@Composable
fun Text.load(): String? {
    return when (this) {
        is Text.Loaded -> text
        is Text.Resource -> stringResource(res)
    }
}

fun Text.toAnnotatedString(context: Context): AnnotatedString? {
    return loadText(context)?.let { AnnotatedString(it) }
}
