package com.android.systemui.animation

/**
 * A [LaunchAnimator.Timings] to be used in tests.
 *
 * Note that all timings except the total duration are non-zero to avoid divide-by-zero exceptions
 * when computing the progress of a sub-animation (the contents fade in/out).
 */
val TEST_TIMINGS = LaunchAnimator.Timings(
    totalDuration = 0L,
    contentBeforeFadeOutDelay = 1L,
    contentBeforeFadeOutDuration = 1L,
    contentAfterFadeInDelay = 1L,
    contentAfterFadeInDuration = 1L
)

/** A [LaunchAnimator.Interpolators] to be used in tests. */
val TEST_INTERPOLATORS = LaunchAnimator.Interpolators(
    positionInterpolator = Interpolators.STANDARD,
    positionXInterpolator = Interpolators.STANDARD,
    contentBeforeFadeOutInterpolator = Interpolators.STANDARD,
    contentAfterFadeInInterpolator = Interpolators.STANDARD
)