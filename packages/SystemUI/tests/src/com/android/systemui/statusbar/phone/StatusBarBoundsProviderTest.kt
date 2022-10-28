/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.StatusBarBoundsProvider.BoundsChangeListener
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class StatusBarBoundsProviderTest : SysuiTestCase() {

    companion object {
        private val START_SIDE_BOUNDS = Rect(50, 100, 150, 200)
        private val END_SIDE_BOUNDS = Rect(250, 300, 350, 400)
    }

    @Mock private lateinit var boundsChangeListener: BoundsChangeListener

    private lateinit var boundsProvider: StatusBarBoundsProvider

    private lateinit var startSideContent: View
    private lateinit var endSideContent: View

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        startSideContent = spy(FrameLayout(context)).apply { setBoundsOnScreen(START_SIDE_BOUNDS) }
        endSideContent = spy(FrameLayout(context)).apply { setBoundsOnScreen(END_SIDE_BOUNDS) }

        boundsProvider =
            StatusBarBoundsProvider(setOf(boundsChangeListener), startSideContent, endSideContent)
    }

    @Test
    fun visibleStartSideBounds_returnsBoundsFromStartSideContentView() {
        assertThat(boundsProvider.visibleStartSideBounds).isEqualTo(START_SIDE_BOUNDS)
    }

    @Test
    fun visibleEndSideBounds_returnsBoundsFromEndSideContentView() {
        assertThat(boundsProvider.visibleEndSideBounds).isEqualTo(END_SIDE_BOUNDS)
    }

    @Test
    fun startBoundsChange_afterStart_notifiesListener() {
        boundsProvider.start()
        val newBounds = Rect(START_SIDE_BOUNDS).apply { left += 1 }

        startSideContent.setBoundsOnScreen(newBounds)

        verify(boundsChangeListener).onStatusBarBoundsChanged()
    }

    @Test
    fun startBoundsChange_beforeStart_doesNotNotifyListener() {
        val newBounds = Rect(START_SIDE_BOUNDS).apply { left += 1 }

        startSideContent.setBoundsOnScreen(newBounds)

        verify(boundsChangeListener, never()).onStatusBarBoundsChanged()
    }

    @Test
    fun startBoundsChange_afterStop_doesNotNotifyListener() {
        boundsProvider.start()
        boundsProvider.stop()
        val newBounds = Rect(START_SIDE_BOUNDS).apply { left += 1 }

        startSideContent.setBoundsOnScreen(newBounds)

        verify(boundsChangeListener, never()).onStatusBarBoundsChanged()
    }

    @Test
    fun startLayoutChange_afterStart_boundsOnScreenSame_doesNotNotifyListener() {
        boundsProvider.start()
        val newBounds = Rect(START_SIDE_BOUNDS).apply { left += 1 }

        startSideContent.layout(newBounds)

        verify(boundsChangeListener, never()).onStatusBarBoundsChanged()
    }

    @Test
    fun endBoundsChange_afterStart_notifiesListener() {
        boundsProvider.start()
        val newBounds = Rect(START_SIDE_BOUNDS).apply { right += 1 }

        endSideContent.setBoundsOnScreen(newBounds)

        verify(boundsChangeListener).onStatusBarBoundsChanged()
    }

    @Test
    fun endBoundsChange_beforeStart_doesNotNotifyListener() {
        val newBounds = Rect(START_SIDE_BOUNDS).apply { right += 1 }

        endSideContent.setBoundsOnScreen(newBounds)

        verify(boundsChangeListener, never()).onStatusBarBoundsChanged()
    }

    @Test
    fun endBoundsChange_afterStop_doesNotNotifyListener() {
        boundsProvider.start()
        boundsProvider.stop()
        val newBounds = Rect(START_SIDE_BOUNDS).apply { right += 1 }

        endSideContent.setBoundsOnScreen(newBounds)

        verify(boundsChangeListener, never()).onStatusBarBoundsChanged()
    }

    @Test
    fun endLayoutChange_afterStart_boundsOnScreenSame_doesNotNotifyListener() {
        boundsProvider.start()
        val newBounds = Rect(START_SIDE_BOUNDS).apply { right += 1 }

        endSideContent.layout(newBounds)

        verify(boundsChangeListener, never()).onStatusBarBoundsChanged()
    }
}

private fun View.setBoundsOnScreen(bounds: Rect) {
    doAnswer { invocation ->
            val boundsOutput = invocation.arguments[0] as Rect
            boundsOutput.set(bounds)
            return@doAnswer Unit
        }
        .`when`(this)
        .getBoundsOnScreen(any())
    layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
}

private fun View.layout(rect: Rect) {
    layout(rect.left, rect.top, rect.right, rect.bottom)
}
