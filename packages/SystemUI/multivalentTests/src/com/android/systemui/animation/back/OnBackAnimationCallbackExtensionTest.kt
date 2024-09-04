package com.android.systemui.animation.back

import android.util.DisplayMetrics
import android.window.BackEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class OnBackAnimationCallbackExtensionTest : SysuiTestCase() {
    private val onBackProgress: (BackTransformation) -> Unit = mock()
    private val onBackStart: (BackEvent) -> Unit = mock()
    private val onBackInvoke: () -> Unit = mock()
    private val onBackCancel: () -> Unit = mock()

    private val displayMetrics =
        DisplayMetrics().apply {
            widthPixels = 100
            heightPixels = 100
            density = 1f
        }

    private val onBackAnimationCallback =
        onBackAnimationCallbackFrom(
            backAnimationSpec = BackAnimationSpec.floatingSystemSurfacesForSysUi { displayMetrics },
            displayMetrics = displayMetrics,
            onBackProgressed = onBackProgress,
            onBackStarted = onBackStart,
            onBackInvoked = onBackInvoke,
            onBackCancelled = onBackCancel,
        )

    @Test
    fun onBackProgressed_shouldInvoke_onBackProgress() {
        val backEvent =
            BackEvent(
                /* touchX = */ 0f,
                /* touchY = */ 0f,
                /* progress = */ 0f,
                /* swipeEdge = */ BackEvent.EDGE_LEFT
            )
        onBackAnimationCallback.onBackStarted(backEvent)

        onBackAnimationCallback.onBackProgressed(backEvent)

        val argumentCaptor = argumentCaptor<BackTransformation>()
        verify(onBackProgress).invoke(capture(argumentCaptor))

        val actual = argumentCaptor.value
        val tolerance = 0.0001f
        assertThat(actual.translateX).isWithin(tolerance).of(0f)
        assertThat(actual.translateY).isWithin(tolerance).of(0f)
        assertThat(actual.scale).isWithin(tolerance).of(1f)
    }

    @Test
    fun onBackStarted_shouldInvoke_onBackStart() {
        val backEvent =
            BackEvent(
                /* touchX = */ 0f,
                /* touchY = */ 0f,
                /* progress = */ 0f,
                /* swipeEdge = */ BackEvent.EDGE_LEFT
            )

        onBackAnimationCallback.onBackStarted(backEvent)

        verify(onBackStart).invoke(backEvent)
    }

    @Test
    fun onBackInvoked_shouldInvoke_onBackInvoke() {
        onBackAnimationCallback.onBackInvoked()

        verify(onBackInvoke).invoke()
    }
}
