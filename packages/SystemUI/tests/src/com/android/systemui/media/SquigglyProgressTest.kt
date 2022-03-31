package com.android.systemui.media

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.graphics.ColorUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class SquigglyProgressTest : SysuiTestCase() {

    private val colorFilter = LightingColorFilter(Color.RED, Color.BLUE)
    private val strokeWidth = 5f
    private val alpha = 128
    private val tint = Color.GREEN

    lateinit var squigglyProgress: SquigglyProgress
    @Mock lateinit var canvas: Canvas
    @Captor lateinit var wavePaintCaptor: ArgumentCaptor<Paint>
    @Captor lateinit var linePaintCaptor: ArgumentCaptor<Paint>
    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    @Before
    fun setup() {
        squigglyProgress = SquigglyProgress()
        squigglyProgress.waveLength = 30f
        squigglyProgress.lineAmplitude = 10f
        squigglyProgress.phaseSpeed = 8f
        squigglyProgress.strokeWidth = strokeWidth
        squigglyProgress.bounds = Rect(0, 0, 300, 30)
    }

    @Test
    fun testDrawPathAndLine() {
        squigglyProgress.draw(canvas)

        verify(canvas).drawPath(any(), wavePaintCaptor.capture())
        verify(canvas).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                linePaintCaptor.capture())
    }

    @Test
    fun testOnLevelChanged() {
        assertThat(squigglyProgress.setLevel(5)).isFalse()
        squigglyProgress.animate = true
        assertThat(squigglyProgress.setLevel(4)).isTrue()
    }

    @Test
    fun testStrokeWidth() {
        squigglyProgress.draw(canvas)

        verify(canvas).drawPath(any(), wavePaintCaptor.capture())
        verify(canvas).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                linePaintCaptor.capture())

        assertThat(wavePaintCaptor.value.strokeWidth).isEqualTo(strokeWidth)
        assertThat(linePaintCaptor.value.strokeWidth).isEqualTo(strokeWidth)
    }

    @Test
    fun testAlpha() {
        squigglyProgress.alpha = alpha
        squigglyProgress.draw(canvas)

        verify(canvas).drawPath(any(), wavePaintCaptor.capture())
        verify(canvas).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                linePaintCaptor.capture())

        assertThat(squigglyProgress.alpha).isEqualTo(alpha)
        assertThat(wavePaintCaptor.value.alpha).isEqualTo(alpha)
        assertThat(linePaintCaptor.value.alpha).isEqualTo((alpha / 255f * DISABLED_ALPHA).toInt())
    }

    @Test
    fun testColorFilter() {
        squigglyProgress.colorFilter = colorFilter
        squigglyProgress.draw(canvas)

        verify(canvas).drawPath(any(), wavePaintCaptor.capture())
        verify(canvas).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                linePaintCaptor.capture())

        assertThat(wavePaintCaptor.value.colorFilter).isEqualTo(colorFilter)
        assertThat(linePaintCaptor.value.colorFilter).isEqualTo(colorFilter)
    }

    @Test
    fun testTint() {
        squigglyProgress.setTint(tint)
        squigglyProgress.draw(canvas)

        verify(canvas).drawPath(any(), wavePaintCaptor.capture())
        verify(canvas).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                linePaintCaptor.capture())

        assertThat(wavePaintCaptor.value.color).isEqualTo(tint)
        assertThat(linePaintCaptor.value.color).isEqualTo(
                ColorUtils.setAlphaComponent(tint, DISABLED_ALPHA))
    }
}