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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipModel

/** Container view that holds all right hand side chips in the status bar. */
@Composable
fun StatusBarPopupChipsContainer(chips: List<PopupChipModel.Shown>, modifier: Modifier = Modifier) {
    //    TODO(b/385353140): Add padding and spacing for this container according to UX specs.
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // TODO(b/385352859): Show `StatusBarPopupChip` here instead of `Text` once it is ready.
            chips.forEach { chip -> Text(text = chip.chipText) }
        }
    }
}
