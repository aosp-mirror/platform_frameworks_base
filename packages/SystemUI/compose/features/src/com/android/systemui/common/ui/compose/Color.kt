/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.common.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.android.compose.theme.colorAttr

/** Resolves [com.android.systemui.common.shared.model.Color] into [Color] */
@Composable
@ReadOnlyComposable
fun com.android.systemui.common.shared.model.Color.toColor(): Color {
    return when (this) {
        is com.android.systemui.common.shared.model.Color.Attribute -> colorAttr(attribute)
        is com.android.systemui.common.shared.model.Color.Loaded -> Color(color)
        is com.android.systemui.common.shared.model.Color.Resource -> colorResource(colorRes)
    }
}
