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

package com.android.systemui.communal.ui.compose.extensions

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Sets whether gestures are allowed on children of this element. */
fun Modifier.allowGestures(allowed: Boolean): Modifier =
    if (allowed) {
        this
    } else {
        this.then(pointerInput(Unit) { consumeAllGestures() })
    }
