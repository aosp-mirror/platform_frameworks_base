/*
 * Copyright 2022 The Android Open Source Project
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

package com.google.android.horologist.compose.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.scrollAway
import com.google.android.horologist.compose.navscaffold.ScalingLazyColumnScrollableState

internal fun Modifier.scrollAway(
        scrollState: State<ScrollableState?>,
): Modifier = composed {
    when (val state = scrollState.value) {
        is ScalingLazyColumnScrollableState -> {
            val offsetDp = with(LocalDensity.current) {
                state.initialOffsetPx.toDp()
            }
            this.scrollAway(state.scalingLazyListState, state.initialIndex, offsetDp)
        }
        is ScalingLazyListState -> this.scrollAway(state)
        is LazyListState -> this.scrollAway(state)
        is ScrollState -> this.scrollAway(state)
        // Disabled
        null -> this.hidden()
        // Enabled but no scroll state
        else -> this
    }
}

internal fun Modifier.hidden(): Modifier = layout { _, _ -> layout(0, 0) {} }
