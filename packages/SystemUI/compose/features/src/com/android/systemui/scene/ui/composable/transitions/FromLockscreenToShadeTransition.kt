package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.TransitionBuilder
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.lockscreenToShadeTransition(
    durationScale: Double = 1.0,
) {
    spec = tween(durationMillis = (DefaultDuration * durationScale).inWholeMilliseconds.toInt())
    toShadeTransition()
}

private val DefaultDuration = 500.milliseconds
