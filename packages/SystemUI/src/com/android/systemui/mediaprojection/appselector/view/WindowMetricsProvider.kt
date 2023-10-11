package com.android.systemui.mediaprojection.appselector.view

import android.graphics.Insets
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import javax.inject.Inject

/** Provides values related to window metrics. */
interface WindowMetricsProvider {

    val maximumWindowBounds: Rect

    val currentWindowInsets: Insets
}

class WindowMetricsProviderImpl
@Inject
constructor(
    private val windowManager: WindowManager,
) : WindowMetricsProvider {
    override val maximumWindowBounds: Rect
        get() = windowManager.maximumWindowMetrics.bounds

    override val currentWindowInsets: Insets
        get() =
            windowManager.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.tappableElement()
            )
}
