package com.android.systemui.animation.back

import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@SmallTest
@RunWith(JUnit4::class)
class BackTransformationTest : SysuiTestCase() {
    private val targetView: View = mock()

    @Test
    fun defaultValue_noTransformation() {
        val transformation = BackTransformation()

        assertThat(transformation.translateX).isNaN()
        assertThat(transformation.translateY).isNaN()
        assertThat(transformation.scale).isNaN()
    }

    @Test
    fun applyTo_targetView_translateX_Y_Scale() {
        val transformation = BackTransformation(translateX = 0f, translateY = 0f, scale = 1f)

        transformation.applyTo(targetView = targetView)

        verify(targetView).translationX = 0f
        verify(targetView).translationY = 0f
        verify(targetView).scaleX = 1f
        verify(targetView).scaleY = 1f
        verifyNoMoreInteractions(targetView)
    }

    @Test
    fun applyTo_targetView_translateX() {
        val transformation = BackTransformation(translateX = 1f)

        transformation.applyTo(targetView = targetView)

        verify(targetView).translationX = 1f
        verifyNoMoreInteractions(targetView)
    }

    @Test
    fun applyTo_targetView_translateY() {
        val transformation = BackTransformation(translateY = 2f)

        transformation.applyTo(targetView = targetView)

        verify(targetView).translationY = 2f
        verifyNoMoreInteractions(targetView)
    }

    @Test
    fun applyTo_targetView_scale() {
        val transformation = BackTransformation(scale = 3f)

        transformation.applyTo(targetView = targetView)

        verify(targetView).scaleX = 3f
        verify(targetView).scaleY = 3f
        verifyNoMoreInteractions(targetView)
    }

    @Test
    fun applyTo_targetView_noTransformation() {
        val transformation = BackTransformation()

        transformation.applyTo(targetView = targetView)

        verifyNoMoreInteractions(targetView)
    }
}
