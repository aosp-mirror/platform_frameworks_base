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

@file:Suppress("ObjectLiteralToLambda")
@file:OptIn(ExperimentalHorologistApi::class, ExperimentalWearFoundationApi::class)

package com.google.android.horologist.compose.layout

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.ScalingParams
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumnState.RotaryMode
import com.google.android.horologist.compose.rotaryinput.rememberDisabledHaptic
import com.google.android.horologist.compose.rotaryinput.rememberRotaryHapticHandler
import com.google.android.horologist.compose.rotaryinput.rotaryWithScroll
import com.google.android.horologist.compose.rotaryinput.rotaryWithSnap
import com.google.android.horologist.compose.rotaryinput.toRotaryScrollAdapter
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults as WearScalingLazyColumnDefaults

/**
 * A Config and State object wrapping up all configuration for a [ScalingLazyColumn].
 * This allows defaults such as [ScalingLazyColumnDefaults.belowTimeText].
 */
@ExperimentalHorologistApi
public class ScalingLazyColumnState(
        public val initialScrollPosition: ScrollPosition = ScrollPosition(1, 0),
        public val autoCentering: AutoCenteringParams? = AutoCenteringParams(
                initialScrollPosition.index,
                initialScrollPosition.offsetPx,
        ),
        public val anchorType: ScalingLazyListAnchorType = ScalingLazyListAnchorType.ItemCenter,
        public val contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
        public val rotaryMode: RotaryMode = RotaryMode.Scroll,
        public val reverseLayout: Boolean = false,
        public val verticalArrangement: Arrangement.Vertical =
                Arrangement.spacedBy(
                        space = 4.dp,
                        alignment = if (!reverseLayout) Alignment.Top else Alignment.Bottom,
                ),
        public val horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
        public val flingBehavior: FlingBehavior? = null,
        public val userScrollEnabled: Boolean = true,
        public val scalingParams: ScalingParams = WearScalingLazyColumnDefaults.scalingParams(),
        public val hapticsEnabled: Boolean = true,
) {
    private var _state: ScalingLazyListState? = null
    public var state: ScalingLazyListState
        get() {
            if (_state == null) {
                _state = ScalingLazyListState(
                        initialScrollPosition.index,
                        initialScrollPosition.offsetPx,
                )
            }
            return _state!!
        }
        set(value) {
            _state = value
        }

    public sealed interface RotaryMode {
        public object Snap : RotaryMode
        public object Scroll : RotaryMode

        @Deprecated(
                "Use RotaryMode.Scroll instead",
                replaceWith = ReplaceWith("RotaryMode.Scroll"),
        )
        public object Fling : RotaryMode
    }

    public data class ScrollPosition(
            val index: Int,
            val offsetPx: Int,
    )

    public fun interface Factory {
        @Composable
        public fun create(): ScalingLazyColumnState
    }
}

@Composable
public fun rememberColumnState(
        factory: ScalingLazyColumnState.Factory = ScalingLazyColumnDefaults.belowTimeText(),
): ScalingLazyColumnState {
    val columnState = factory.create()

    columnState.state = rememberSaveable(saver = ScalingLazyListState.Saver) {
        columnState.state
    }

    return columnState
}

@ExperimentalHorologistApi
@Composable
public fun ScalingLazyColumn(
        columnState: ScalingLazyColumnState,
        modifier: Modifier = Modifier,
        content: ScalingLazyListScope.() -> Unit,
) {
    val focusRequester = rememberActiveFocusRequester()

    val rotaryHaptics = if (columnState.hapticsEnabled) {
        rememberRotaryHapticHandler(columnState.state)
    } else {
        rememberDisabledHaptic()
    }
    val modifierWithRotary = when (columnState.rotaryMode) {
        RotaryMode.Snap -> modifier.rotaryWithSnap(
                focusRequester = focusRequester,
                rotaryScrollAdapter = columnState.state.toRotaryScrollAdapter(),
                reverseDirection = columnState.reverseLayout,
                rotaryHaptics = rotaryHaptics,
        )

        else -> modifier.rotaryWithScroll(
                focusRequester = focusRequester,
                scrollableState = columnState.state,
                reverseDirection = columnState.reverseLayout,
                rotaryHaptics = rotaryHaptics,
        )
    }

    ScalingLazyColumn(
            modifier = modifierWithRotary,
            state = columnState.state,
            contentPadding = columnState.contentPadding,
            reverseLayout = columnState.reverseLayout,
            verticalArrangement = columnState.verticalArrangement,
            horizontalAlignment = columnState.horizontalAlignment,
            flingBehavior = columnState.flingBehavior ?: ScrollableDefaults.flingBehavior(),
            userScrollEnabled = columnState.userScrollEnabled,
            scalingParams = columnState.scalingParams,
            anchorType = columnState.anchorType,
            autoCentering = columnState.autoCentering,
            content = content,
    )
}
