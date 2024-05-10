package com.android.compose.ui.util

import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.Scale
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MathHelpersTest {

    @Test
    fun lerpScaleWithPivotUnspecified() {
        val scale1 = Scale(1f, 1f)
        val scale2 = Scale(5f, 3f)
        val expectedScale = Scale(3f, 2f)

        val actualScale = lerp(scale1, scale2, 0.5f)

        assertThat(actualScale).isEqualTo(expectedScale)
    }

    @Test
    fun lerpScaleWithFirstPivotSpecified() {
        val scale1 = Scale(1f, 1f, Offset(1f, 1f))
        val scale2 = Scale(5f, 3f)
        val expectedScale = Scale(3f, 2f, Offset(1f, 1f))

        val actualScale = lerp(scale1, scale2, 0.5f)

        assertThat(actualScale).isEqualTo(expectedScale)
    }

    @Test
    fun lerpScaleWithSecondPivotSpecified() {
        val scale1 = Scale(1f, 1f)
        val scale2 = Scale(5f, 3f, Offset(1f, 1f))
        val expectedScale = Scale(3f, 2f, Offset(1f, 1f))

        val actualScale = lerp(scale1, scale2, 0.5f)

        assertThat(actualScale).isEqualTo(expectedScale)
    }

    @Test
    fun lerpScaleWithBothPivotsSpecified() {
        val scale1 = Scale(1f, 1f, Offset(1f, 1f))
        val scale2 = Scale(5f, 3f, Offset(3f, 5f))
        val expectedScale = Scale(3f, 2f, Offset(2f, 3f))

        val actualScale = lerp(scale1, scale2, 0.5f)

        assertThat(actualScale).isEqualTo(expectedScale)
    }
}
