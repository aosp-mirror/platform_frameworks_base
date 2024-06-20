/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.ui.viewmodel

import android.content.Context
import android.util.TypedValue
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.res.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Holds UI state and handles user input for the pattern bouncer UI. */
class PatternBouncerViewModel(
    private val applicationContext: Context,
    viewModelScope: CoroutineScope,
    interactor: BouncerInteractor,
    isInputEnabled: StateFlow<Boolean>,
    private val onIntentionalUserInput: () -> Unit,
) :
    AuthMethodBouncerViewModel(
        viewModelScope = viewModelScope,
        interactor = interactor,
        isInputEnabled = isInputEnabled,
    ) {

    /** The number of columns in the dot grid. */
    val columnCount = 3

    /** The number of rows in the dot grid. */
    val rowCount = 3

    private val _selectedDots = MutableStateFlow<LinkedHashSet<PatternDotViewModel>>(linkedSetOf())

    /** The dots that were selected by the user, in the order of selection. */
    val selectedDots: StateFlow<List<PatternDotViewModel>> =
        _selectedDots
            .map { it.toList() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptyList(),
            )

    private val _currentDot = MutableStateFlow<PatternDotViewModel?>(null)

    /** The most-recently selected dot that the user selected. */
    val currentDot: StateFlow<PatternDotViewModel?> = _currentDot.asStateFlow()

    private val _dots = MutableStateFlow(defaultDots())

    /** All dots on the grid. */
    val dots: StateFlow<List<PatternDotViewModel>> = _dots.asStateFlow()

    /** Whether the pattern itself should be rendered visibly. */
    val isPatternVisible: StateFlow<Boolean> = interactor.isPatternVisible

    override val authenticationMethod = AuthenticationMethodModel.Pattern

    override val lockoutMessageId = R.string.kg_too_many_failed_pattern_attempts_dialog_message

    /** Notifies that the user has started a drag gesture across the dot grid. */
    fun onDragStart() {
        onIntentionalUserInput()
    }

    /**
     * Notifies that the user is dragging across the dot grid.
     *
     * @param xPx The horizontal coordinate of the position of the user's pointer, in pixels.
     * @param yPx The vertical coordinate of the position of the user's pointer, in pixels.
     * @param containerSizePx The size of the container of the dot grid, in pixels. It's assumed
     *   that the dot grid is perfectly square such that width and height are equal.
     */
    fun onDrag(xPx: Float, yPx: Float, containerSizePx: Int) {
        val cellWidthPx = containerSizePx / columnCount
        val cellHeightPx = containerSizePx / rowCount

        if (xPx < 0 || yPx < 0) {
            return
        }

        val dotColumn = (xPx / cellWidthPx).toInt()
        val dotRow = (yPx / cellHeightPx).toInt()
        if (dotColumn > columnCount - 1 || dotRow > rowCount - 1) {
            return
        }

        val dotPixelX = dotColumn * cellWidthPx + cellWidthPx / 2
        val dotPixelY = dotRow * cellHeightPx + cellHeightPx / 2

        val distance = sqrt((xPx - dotPixelX).pow(2) + (yPx - dotPixelY).pow(2))
        val hitRadius = hitFactor * min(cellWidthPx, cellHeightPx) / 2
        if (distance > hitRadius) {
            return
        }

        val hitDot = dots.value.firstOrNull { dot -> dot.x == dotColumn && dot.y == dotRow }
        if (hitDot != null && !_selectedDots.value.contains(hitDot)) {
            val skippedOverDots =
                currentDot.value?.let { previousDot ->
                    buildList {
                        var dot = previousDot
                        while (dot != hitDot) {
                            // Move along the direction of the line connecting the previously
                            // selected dot and current hit dot, and see if they were skipped over
                            // but fall on that line.
                            if (dot.isOnLineSegment(previousDot, hitDot)) {
                                add(dot)
                            }
                            dot =
                                PatternDotViewModel(
                                    x =
                                        if (hitDot.x > dot.x) {
                                            dot.x + 1
                                        } else if (hitDot.x < dot.x) dot.x - 1 else dot.x,
                                    y =
                                        if (hitDot.y > dot.y) {
                                            dot.y + 1
                                        } else if (hitDot.y < dot.y) dot.y - 1 else dot.y,
                                )
                        }
                    }
                }
                    ?: emptyList()

            _selectedDots.value =
                linkedSetOf<PatternDotViewModel>().apply {
                    addAll(_selectedDots.value)
                    addAll(skippedOverDots)
                    add(hitDot)
                }
            _currentDot.value = hitDot
        }
    }

    /** Notifies that the user has ended the drag gesture across the dot grid. */
    fun onDragEnd() {
        val pattern = getInput()
        if (pattern.size == 1) {
            // Single dot patterns are treated as erroneous/false taps:
            interactor.onFalseUserInput()
        }

        clearInput()
        tryAuthenticate(input = pattern)
    }

    override fun clearInput() {
        _dots.value = defaultDots()
        _currentDot.value = null
        _selectedDots.value = linkedSetOf()
    }

    override fun getInput(): List<Any> {
        return _selectedDots.value.map(PatternDotViewModel::toCoordinate)
    }

    private fun defaultDots(): List<PatternDotViewModel> {
        return buildList {
            (0 until columnCount).forEach { x ->
                (0 until rowCount).forEach { y ->
                    add(
                        PatternDotViewModel(
                            x = x,
                            y = y,
                        )
                    )
                }
            }
        }
    }

    private val hitFactor: Float by lazy {
        val outValue = TypedValue()
        applicationContext.resources.getValue(
            com.android.internal.R.dimen.lock_pattern_dot_hit_factor,
            outValue,
            true
        )
        max(min(outValue.float, 1f), MIN_DOT_HIT_FACTOR)
    }

    companion object {
        private const val MIN_DOT_HIT_FACTOR = 0.2f
    }
}

/**
 * Determines whether [this] dot is present on the line segment connecting [first] and [second]
 * dots.
 */
private fun PatternDotViewModel.isOnLineSegment(
    first: PatternDotViewModel,
    second: PatternDotViewModel
): Boolean {
    val anotherPoint = this
    // No need to consider any points outside the bounds of two end points
    val isWithinBounds =
        anotherPoint.x.isBetween(first.x, second.x) && anotherPoint.y.isBetween(first.y, second.y)
    if (!isWithinBounds) {
        return false
    }

    // Uses the 2 point line equation: (y-y1)/(x-x1) = (y2-y1)/(x2-x1)
    // which can be rewritten as:      (y-y1)*(x2-x1) = (x-x1)*(y2-y1)
    // This is true for any point on the line passing through these two points
    return (anotherPoint.y - first.y) * (second.x - first.x) ==
        (anotherPoint.x - first.x) * (second.y - first.y)
}

/** Is [this] Int between [a] and [b] */
private fun Int.isBetween(a: Int, b: Int): Boolean {
    return (this in a..b) || (this in b..a)
}

data class PatternDotViewModel(
    val x: Int,
    val y: Int,
) {
    fun toCoordinate(): AuthenticationPatternCoordinate {
        return AuthenticationPatternCoordinate(
            x = x,
            y = y,
        )
    }
}
