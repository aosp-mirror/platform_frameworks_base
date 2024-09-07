package com.android.systemui.scene.ui.composable.transitions

import com.android.compose.animation.scene.TransitionBuilder
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.goneToQuickSettingsTransition(
    durationScale: Double = 1.0,
) {
    toQuickSettingsTransition(durationScale = durationScale)
}

private val DefaultDuration = 500.milliseconds
