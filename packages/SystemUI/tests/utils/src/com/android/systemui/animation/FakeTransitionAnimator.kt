/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.animation

import com.android.app.animation.Interpolators

/** A [TransitionAnimator] to be used in tests. */
fun fakeTransitionAnimator(): TransitionAnimator {
    return TransitionAnimator(TEST_TIMINGS, TEST_INTERPOLATORS)
}

/**
 * A [TransitionAnimator.Timings] to be used in tests.
 *
 * Note that all timings except the total duration are non-zero to avoid divide-by-zero exceptions
 * when computing the progress of a sub-animation (the contents fade in/out).
 */
private val TEST_TIMINGS =
    TransitionAnimator.Timings(
        totalDuration = 0L,
        contentBeforeFadeOutDelay = 1L,
        contentBeforeFadeOutDuration = 1L,
        contentAfterFadeInDelay = 1L,
        contentAfterFadeInDuration = 1L
    )

/** A [TransitionAnimator.Interpolators] to be used in tests. */
private val TEST_INTERPOLATORS =
    TransitionAnimator.Interpolators(
        positionInterpolator = Interpolators.STANDARD,
        positionXInterpolator = Interpolators.STANDARD,
        contentBeforeFadeOutInterpolator = Interpolators.STANDARD,
        contentAfterFadeInInterpolator = Interpolators.STANDARD
    )
