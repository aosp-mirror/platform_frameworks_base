package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.TransitionBuilder
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.lockscreenToQuickSettingsTransition(
    durationScale: Double = 1.0,
) {
    spec = tween(durationMillis = (DefaultDuration * durationScale).inWholeMilliseconds.toInt())
    toQuickSettingsTransition()
}

private val DefaultDuration = 500.milliseconds
