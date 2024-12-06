/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.wm.shell.compatui.letterbox

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn
import com.android.wm.shell.compatui.letterbox.LetterboxEvents.motionEventAt
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.verify

/**
 * Tests for [LetterboxGestureDelegate].
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:LetterboxGestureDelegateTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class LetterboxGestureDelegateTest {

    class DelegateTest : LetterboxGestureListener by LetterboxGestureDelegate

    val delegate = DelegateTest()

    @Before
    fun setUp() {
        spyOn(LetterboxGestureDelegate)
    }

    @Test
    fun `When delegating all methods are invoked`() {
        val event = motionEventAt(0f, 0f)
        with(delegate) {
            onDown(event)
            onShowPress(event)
            onSingleTapUp(event)
            onScroll(event, event, 0f, 0f)
            onFling(event, event, 0f, 0f)
            onLongPress(event)
            onSingleTapConfirmed(event)
            onDoubleTap(event)
            onDoubleTapEvent(event)
            onContextClick(event)
        }
        with(LetterboxGestureDelegate) {
            verify(this).onDown(event)
            verify(this).onShowPress(event)
            verify(this).onSingleTapUp(event)
            verify(this).onScroll(event, event, 0f, 0f)
            verify(this).onFling(event, event, 0f, 0f)
            verify(this).onLongPress(event)
            verify(this).onSingleTapConfirmed(event)
            verify(this).onDoubleTap(event)
            verify(this).onDoubleTapEvent(event)
            verify(this).onContextClick(event)
        }
    }
}
