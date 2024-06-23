/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.keyguard

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class NumPadAnimatorTest : SysuiTestCase() {
    @Mock lateinit var background: GradientDrawable
    @Mock lateinit var buttonImage: Drawable
    private lateinit var underTest: NumPadAnimator

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest = NumPadAnimator(context, background, 0, buttonImage)
    }

    @Test
    fun testOnLayout() {
        underTest.onLayout(100, 100)
        verify(background).cornerRadius = 50f
        reset(background)
        underTest.onLayout(100, 100)
        verify(background, never()).cornerRadius = anyFloat()
    }
}
