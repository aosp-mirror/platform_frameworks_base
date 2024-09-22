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

package com.android.systemui.qs.composefragment.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.qs.shared.ui.ElementKeys

/**
 * This composable is used at the start of the tiles in QQS and QS to anchor the expansion and be
 * able to have relative anchor translation of elements that appear in QS.
 */
@Composable
fun SceneScope.GridAnchor(modifier: Modifier = Modifier) {
    // The size of this anchor does not matter, as the tiles don't change size on expansion.
    Spacer(modifier.element(ElementKeys.GridAnchor))
}
