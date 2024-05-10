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

package com.android.keyguard

import android.content.Context
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.util.AttributeSet
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class BouncerKeyguardMessageAreaTest : SysuiTestCase() {
    class FakeBouncerKeyguardMessageArea(context: Context, attrs: AttributeSet?) :
        BouncerKeyguardMessageArea(context, attrs) {
        override val SHOW_DURATION_MILLIS = 0L
        override val HIDE_DURATION_MILLIS = 0L
    }
    lateinit var underTest: BouncerKeyguardMessageArea

    @Before
    fun setup() {
        underTest = FakeBouncerKeyguardMessageArea(context, null)
    }

    @Test
    fun testSetSameMessage() {
        val underTestSpy = spy(underTest)
        underTestSpy.setMessage("abc", animate = true)
        underTestSpy.setMessage("abc", animate = true)
        verify(underTestSpy, times(1)).text = "abc"
    }

    @Test
    fun testSetDifferentMessage() {
        underTest.setMessage("abc", animate = true)
        underTest.setMessage("def", animate = true)
        assertThat(underTest.text).isEqualTo("def")
    }

    @Test
    fun testSetNullMessage() {
        underTest.setMessage(null, animate = true)
        assertThat(underTest.text).isEqualTo("")
    }

    @Test
    fun testSetNullClearsPreviousMessage() {
        underTest.setMessage("something not null", animate = true)
        assertThat(underTest.text).isEqualTo("something not null")

        underTest.setMessage(null, animate = true)
        assertThat(underTest.text).isEqualTo("")
    }
}
