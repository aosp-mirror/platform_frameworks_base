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

package com.android.systemui.statusbar.chips.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel

@Composable
fun OngoingActivityChips(chips: MultipleOngoingActivityChipsModel, modifier: Modifier = Modifier) {
    Row(
        // TODO(b/372657935): Remove magic numbers for padding and spacing.
        modifier = modifier.fillMaxHeight().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // TODO(b/372657935): Make sure chips are only shown when there is enough horizontal space.
        chips.active
            .filter { !it.isHidden }
            .forEach { key(it.key) { OngoingActivityChip(model = it) } }
    }
}
