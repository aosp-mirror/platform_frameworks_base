/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.animation

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.testing.AndroidTestingRunner
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlin.math.ceil
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

private val PAINT = TextPaint().apply {
    textSize = 32f
}

@RunWith(AndroidTestingRunner::class)
@SmallTest
class TextAnimatorTest : SysuiTestCase() {

    private fun makeLayout(text: String, paint: TextPaint): Layout {
        val width = ceil(Layout.getDesiredWidth(text, 0, text.length, paint)).toInt()
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
    }

    @Test
    fun testAnimationStarted() {
        val layout = makeLayout("Hello, World", PAINT)
        val valueAnimator = mock(ValueAnimator::class.java)
        val textInterpolator = mock(TextInterpolator::class.java)
        val paint = mock(TextPaint::class.java)
        `when`(textInterpolator.targetPaint).thenReturn(paint)

        val textAnimator = TextAnimator(layout, {}).apply {
            this.textInterpolator = textInterpolator
            this.animator = valueAnimator
        }

        textAnimator.setTextStyle(
                weight = 400,
                animate = true
        )

        // If animation is requested, the base state should be rebased and the target state should
        // be updated.
        val order = inOrder(textInterpolator)
        order.verify(textInterpolator).rebase()
        order.verify(textInterpolator).onTargetPaintModified()

        // In case of animation, should not shape the base state since the animation should start
        // from current state.
        verify(textInterpolator, never()).onBasePaintModified()

        // Then, animation should be started.
        verify(valueAnimator, times(1)).start()
    }

    @Test
    fun testAnimationNotStarted() {
        val layout = makeLayout("Hello, World", PAINT)
        val valueAnimator = mock(ValueAnimator::class.java)
        val textInterpolator = mock(TextInterpolator::class.java)
        val paint = mock(TextPaint::class.java)
        `when`(textInterpolator.targetPaint).thenReturn(paint)

        val textAnimator = TextAnimator(layout, {}).apply {
            this.textInterpolator = textInterpolator
            this.animator = valueAnimator
        }

        textAnimator.setTextStyle(
                weight = 400,
                animate = false
        )

        // If animation is not requested, the progress should be 1 which is end of animation and the
        // base state is rebased to target state by calling rebase.
        val order = inOrder(textInterpolator)
        order.verify(textInterpolator).onTargetPaintModified()
        order.verify(textInterpolator).progress = 1f
        order.verify(textInterpolator).rebase()

        // Then, animation start should not be called.
        verify(valueAnimator, never()).start()
    }

    @Test
    fun testAnimationEnded() {
        val layout = makeLayout("Hello, World", PAINT)
        val valueAnimator = mock(ValueAnimator::class.java)
        val textInterpolator = mock(TextInterpolator::class.java)
        val paint = mock(TextPaint::class.java)
        `when`(textInterpolator.targetPaint).thenReturn(paint)
        val animationEndCallback = mock(Runnable::class.java)

        val textAnimator = TextAnimator(layout, {}).apply {
            this.textInterpolator = textInterpolator
            this.animator = valueAnimator
        }

        textAnimator.setTextStyle(
                weight = 400,
                animate = true,
                onAnimationEnd = animationEndCallback
        )

        // Verify animationEnd callback has been added.
        val captor = ArgumentCaptor.forClass(AnimatorListenerAdapter::class.java)
        verify(valueAnimator).addListener(captor.capture())
        captor.value.onAnimationEnd(valueAnimator)

        // Verify animationEnd callback has been invoked and removed.
        verify(animationEndCallback).run()
        verify(valueAnimator).removeListener(eq(captor.value))
    }

    @Test
    fun testCacheTypeface() {
        val layout = makeLayout("Hello, World", PAINT)
        val valueAnimator = mock(ValueAnimator::class.java)
        val textInterpolator = mock(TextInterpolator::class.java)
        val paint = TextPaint().apply {
            typeface = Typeface.createFromFile("/system/fonts/Roboto-Regular.ttf")
        }
        `when`(textInterpolator.targetPaint).thenReturn(paint)

        val textAnimator = TextAnimator(layout, {}).apply {
            this.textInterpolator = textInterpolator
            this.animator = valueAnimator
        }

        textAnimator.setTextStyle(
                weight = 400,
                animate = true
        )

        val prevTypeface = paint.typeface

        textAnimator.setTextStyle(
                weight = 700,
                animate = true
        )

        assertThat(paint.typeface).isNotSameInstanceAs(prevTypeface)

        textAnimator.setTextStyle(
                weight = 400,
                animate = true
        )

        assertThat(paint.typeface).isSameInstanceAs(prevTypeface)
    }
}
