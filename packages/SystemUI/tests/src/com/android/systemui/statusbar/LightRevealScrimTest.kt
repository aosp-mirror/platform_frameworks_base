/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar

import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.function.Consumer

@RunWith(AndroidTestingRunner::class)
@SmallTest
class LightRevealScrimTest : SysuiTestCase() {

  private lateinit var scrim: LightRevealScrim
  private var isOpaque = false

  @Before
  fun setUp() {
    scrim = LightRevealScrim(context, null, DEFAULT_WIDTH, DEFAULT_HEIGHT)
    scrim.isScrimOpaqueChangedListener = Consumer { opaque ->
      isOpaque = opaque
    }
    scrim.revealAmount = 0f
    assertTrue("Scrim is not opaque in initial setup", scrim.isScrimOpaque)
  }

  @Test
  fun testAlphaSetsOpaque() {
    scrim.alpha = 0.5f
    assertFalse("Scrim is opaque even though alpha is set", scrim.isScrimOpaque)
  }

  @Test
  fun testVisibilitySetsOpaque() {
    scrim.visibility = View.INVISIBLE
    assertFalse("Scrim is opaque even though it's invisible", scrim.isScrimOpaque)
    scrim.visibility = View.GONE
    assertFalse("Scrim is opaque even though it's gone", scrim.isScrimOpaque)
  }

  @Test
  fun testRevealSetsOpaque() {
    scrim.revealAmount = 0.5f
    assertFalse("Scrim is opaque even though it's revealed", scrim.isScrimOpaque)
  }

  @Test
  fun testBeforeOnMeasure_defaultDimensions() {
    assertThat(scrim.viewWidth).isEqualTo(DEFAULT_WIDTH)
    assertThat(scrim.viewHeight).isEqualTo(DEFAULT_HEIGHT)
  }

  @Test
  fun testAfterOnMeasure_measuredDimensions() {
    scrim.measure(/* widthMeasureSpec= */ exact(1), /* heightMeasureSpec= */ exact(2))

    assertThat(scrim.viewWidth).isEqualTo(1)
    assertThat(scrim.viewHeight).isEqualTo(2)
  }

  private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)

  private companion object {
    private const val DEFAULT_WIDTH = 42
    private const val DEFAULT_HEIGHT = 24
  }
}