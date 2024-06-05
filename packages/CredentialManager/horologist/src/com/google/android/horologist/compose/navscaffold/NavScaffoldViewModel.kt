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

@file:OptIn(ExperimentalHorologistApi::class, SavedStateHandleSaveableApi::class)

package com.google.android.horologist.compose.navscaffold

import android.os.Bundle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumnState
import com.google.android.horologist.compose.navscaffold.NavScaffoldViewModel.PositionIndicatorMode
import com.google.android.horologist.compose.navscaffold.NavScaffoldViewModel.TimeTextMode
import com.google.android.horologist.compose.navscaffold.NavScaffoldViewModel.TimeTextMode.ScrollAway
import com.google.android.horologist.compose.navscaffold.NavScaffoldViewModel.VignetteMode.Off
import com.google.android.horologist.compose.navscaffold.NavScaffoldViewModel.VignetteMode.On
import com.google.android.horologist.compose.navscaffold.NavScaffoldViewModel.VignetteMode.WhenScrollable

/**
 * A ViewModel that backs the WearNavScaffold to allow each composable to interact and effect
 * the [Scaffold] positionIndicator, vignette and timeText.
 *
 * A ViewModel is used to allow the same current instance to be shared between the WearNavScaffold
 * and the composable screen via [NavHostController.currentBackStackEntry].
 */
public open class NavScaffoldViewModel(
        private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    internal var initialIndex: Int? = null
    internal var initialOffsetPx: Int? = null
    internal var scrollType by mutableStateOf<ScrollType?>(null)

    private lateinit var _scrollableState: ScrollableState

    /**
     * Returns the scrollable state for this composable or null if the scaffold should
     * not consider this element to be scrollable.
     */
    public val scrollableState: ScrollableState?
        get() = if (scrollType == null || scrollType == ScrollType.None) {
            null
        } else {
            _scrollableState
        }

    /**
     * The configuration of [Vignette], [WhenScrollable], [Off], [On] and if so whether top and
     * bottom. Defaults to on for scrollable screens.
     */
    public var vignettePosition: VignetteMode by mutableStateOf(WhenScrollable)

    /**
     * The configuration of [TimeText], defaults to [TimeTextMode.ScrollAway] which will move the
     * time text above the screen to avoid overlapping with the content moving up.
     */
    public var timeTextMode: TimeTextMode by mutableStateOf(ScrollAway)

    /**
     * The configuration of [PositionIndicator].  The default is to show a scroll bar while the
     * scroll is in progress.
     */
    public var positionIndicatorMode: PositionIndicatorMode
            by mutableStateOf(PositionIndicatorMode.On)

    internal fun initializeScrollState(scrollStateBuilder: () -> ScrollState): ScrollState {
        check(scrollType == null || scrollType == ScrollType.ScrollState)

        if (scrollType == null) {
            scrollType = ScrollType.ScrollState

            _scrollableState = savedStateHandle.saveable(
                    key = "navScaffold.ScrollState",
                    saver = ScrollState.Saver,
            ) {
                scrollStateBuilder()
            }
        }

        return _scrollableState as ScrollState
    }

    internal fun initializeScalingLazyListState(
            scrollableStateBuilder: () -> ScalingLazyListState,
    ): ScalingLazyListState {
        check(scrollType == null || scrollType == ScrollType.ScalingLazyColumn)

        if (scrollType == null) {
            scrollType = ScrollType.ScalingLazyColumn

            _scrollableState = savedStateHandle.saveable(
                    key = "navScaffold.ScalingLazyListState",
                    saver = ScalingLazyListState.Saver,
            ) {
                scrollableStateBuilder().also {
                    initialIndex = it.centerItemIndex
                    initialOffsetPx = it.centerItemScrollOffset
                }
            }
        }

        return _scrollableState as ScalingLazyListState
    }

    internal fun initializeScalingLazyListState(
            columnState: ScalingLazyColumnState,
    ) {
        check(scrollType == null || scrollType == ScrollType.ScalingLazyColumn)

        if (scrollType == null) {
            scrollType = ScrollType.ScalingLazyColumn

            initialIndex = columnState.initialScrollPosition.index
            initialOffsetPx = columnState.initialScrollPosition.offsetPx

            _scrollableState = savedStateHandle.saveable(
                    key = "navScaffold.ScalingLazyListState",
                    saver = ScalingLazyListState.Saver,
            ) {
                columnState.state
            }
        }

        columnState.state = _scrollableState as ScalingLazyListState
    }

    internal fun initializeLazyList(
            scrollableStateBuilder: () -> LazyListState,
    ): LazyListState {
        check(scrollType == null || scrollType == ScrollType.LazyList)

        if (scrollType == null) {
            scrollType = ScrollType.LazyList

            _scrollableState = savedStateHandle.saveable(
                    key = "navScaffold.LazyListState",
                    saver = LazyListState.Saver,
            ) {
                scrollableStateBuilder()
            }
        }

        return _scrollableState as LazyListState
    }

    internal enum class ScrollType {
        None, ScalingLazyColumn, ScrollState, LazyList
    }

    /**
     * The configuration of [TimeText], defaults to [ScrollAway] which will move the time text above the
     * screen to avoid overlapping with the content moving up.
     */
    public enum class TimeTextMode {
        On, Off, ScrollAway
    }

    /**
     * The configuration of [PositionIndicator].  The default is to show a scroll bar while the
     * scroll is in progress.
     */
    public enum class PositionIndicatorMode {
        On, Off
    }

    /**
     * The configuration of [Vignette], [WhenScrollable], [Off], [On] and if so whether top and
     * bottom. Defaults to on for scrollable screens.
     */
    public sealed interface VignetteMode {
        public object WhenScrollable : VignetteMode
        public object Off : VignetteMode
        public data class On(val position: VignettePosition) : VignetteMode
    }

    internal fun timeTextScrollableState(): ScrollableState? {
        return when (timeTextMode) {
            ScrollAway -> {
                when (this.scrollType) {
                    ScrollType.ScrollState -> {
                        this.scrollableState as ScrollState
                    }

                    ScrollType.ScalingLazyColumn -> {
                        val scalingLazyListState =
                                this.scrollableState as ScalingLazyListState

                        ScalingLazyColumnScrollableState(scalingLazyListState, initialIndex
                                ?: 1, initialOffsetPx ?: 0)
                    }

                    ScrollType.LazyList -> {
                        this.scrollableState as LazyListState
                    }

                    else -> {
                        ScrollState(0)
                    }
                }
            }

            TimeTextMode.On -> {
                ScrollState(0)
            }

            else -> {
                null
            }
        }
    }
}

internal class ScalingLazyColumnScrollableState(
        val scalingLazyListState: ScalingLazyListState,
        val initialIndex: Int,
        val initialOffsetPx: Int,
) : ScrollableState by scalingLazyListState

/**
 * The context items provided to a navigation composable.
 *
 * The [viewModel] can be used to customise the scaffold behaviour.
 */
public data class ScaffoldContext<T : ScrollableState>(
        val backStackEntry: NavBackStackEntry,
        val scrollableState: T,
        val viewModel: NavScaffoldViewModel,
) {
    var timeTextMode: TimeTextMode by viewModel::timeTextMode

    var positionIndicatorMode: PositionIndicatorMode by viewModel::positionIndicatorMode

    val arguments: Bundle?
        get() = backStackEntry.arguments
}

public data class NonScrollableScaffoldContext(
        val backStackEntry: NavBackStackEntry,
        val viewModel: NavScaffoldViewModel,
) {
    var timeTextMode: TimeTextMode by viewModel::timeTextMode

    var positionIndicatorMode: PositionIndicatorMode by viewModel::positionIndicatorMode

    val arguments: Bundle?
        get() = backStackEntry.arguments
}

/**
 * The context items provided to a navigation composable.
 *
 * The [viewModel] can be used to customise the scaffold behaviour.
 */
public data class ScrollableScaffoldContext(
        val backStackEntry: NavBackStackEntry,
        val columnState: ScalingLazyColumnState,
        val viewModel: NavScaffoldViewModel,
) {
    val scrollableState: ScalingLazyListState
        get() = columnState.state

    var timeTextMode: TimeTextMode by viewModel::timeTextMode

    var positionIndicatorMode: PositionIndicatorMode by viewModel::positionIndicatorMode

    val arguments: Bundle?
        get() = backStackEntry.arguments
}
