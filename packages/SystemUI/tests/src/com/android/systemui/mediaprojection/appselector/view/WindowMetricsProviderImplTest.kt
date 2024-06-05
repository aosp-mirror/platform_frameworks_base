package com.android.systemui.mediaprojection.appselector.view

import android.graphics.Insets
import android.graphics.Rect
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.core.view.WindowInsetsCompat
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class WindowMetricsProviderImplTest : SysuiTestCase() {

    private val windowManager = mock<WindowManager>()
    private val windowMetricsProvider = WindowMetricsProviderImpl(windowManager)

    @Test
    fun getMaximumWindowBounds_returnsValueFromWMMaxWindowMetrics() {
        val bounds = Rect(/* left= */ 100, /* top= */ 200, /* right= */ 300, /* bottom= */ 400)
        val metrics =
            WindowMetrics(bounds, /* windowInsetsSupplier= */ { null }, /* density= */ 1.0f)
        whenever(windowManager.maximumWindowMetrics).thenReturn(metrics)

        assertThat(windowMetricsProvider.maximumWindowBounds).isEqualTo(bounds)
    }

    @Test
    fun getCurrentWindowInsets_returnsFromWMCurrentWindowMetrics() {
        val bounds = Rect()
        val insets =
            Insets.of(Rect(/* left= */ 123, /* top= */ 456, /* right= */ 789, /* bottom= */ 1012))
        val windowInsets =
            android.view.WindowInsets.Builder()
                .setInsets(WindowInsetsCompat.Type.tappableElement(), insets)
                .build()
        whenever(windowManager.currentWindowMetrics).thenReturn(WindowMetrics(bounds, windowInsets))

        assertThat(windowMetricsProvider.currentWindowInsets).isEqualTo(insets)
    }
}
