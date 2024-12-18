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

package com.android.systemui.media.controls.ui.view

import android.testing.TestableLooper
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PageIndicator
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class MediaCarouselScrollHandlerTest : SysuiTestCase() {

    private val carouselWidth = 1038
    private val motionEventUp = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)

    @Mock lateinit var mediaCarousel: MediaScrollView
    @Mock lateinit var pageIndicator: PageIndicator
    @Mock lateinit var dismissCallback: () -> Unit
    @Mock lateinit var translationChangedListener: () -> Unit
    @Mock lateinit var seekBarUpdateListener: (visibleToUser: Boolean) -> Unit
    @Mock lateinit var closeGuts: (immediate: Boolean) -> Unit
    @Mock lateinit var falsingManager: FalsingManager
    @Mock lateinit var logSmartspaceImpression: (Boolean) -> Unit
    @Mock lateinit var logger: MediaUiEventLogger

    lateinit var executor: FakeExecutor
    private val clock = FakeSystemClock()

    private lateinit var mediaCarouselScrollHandler: MediaCarouselScrollHandler

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(clock)
        mediaCarouselScrollHandler =
            MediaCarouselScrollHandler(
                mediaCarousel,
                pageIndicator,
                executor,
                dismissCallback,
                translationChangedListener,
                seekBarUpdateListener,
                closeGuts,
                falsingManager,
                logSmartspaceImpression,
                logger
            )
        mediaCarouselScrollHandler.playerWidthPlusPadding = carouselWidth

        whenever(mediaCarousel.touchListener).thenReturn(mediaCarouselScrollHandler.touchListener)
    }

    @Test
    fun testCarouselScroll_shortScroll() {
        whenever(mediaCarousel.isLayoutRtl).thenReturn(false)
        whenever(mediaCarousel.relativeScrollX).thenReturn(300)
        whenever(mediaCarousel.scrollX).thenReturn(300)

        mediaCarousel.touchListener?.onTouchEvent(motionEventUp)
        executor.runAllReady()

        verify(mediaCarousel).smoothScrollTo(eq(0), anyInt())
    }

    @Test
    fun testCarouselScroll_shortScroll_isRTL() {
        whenever(mediaCarousel.isLayoutRtl).thenReturn(true)
        whenever(mediaCarousel.relativeScrollX).thenReturn(300)
        whenever(mediaCarousel.scrollX).thenReturn(carouselWidth - 300)

        mediaCarousel.touchListener?.onTouchEvent(motionEventUp)
        executor.runAllReady()

        verify(mediaCarousel).smoothScrollTo(eq(carouselWidth), anyInt())
    }

    @Test
    fun testCarouselScroll_longScroll() {
        whenever(mediaCarousel.isLayoutRtl).thenReturn(false)
        whenever(mediaCarousel.relativeScrollX).thenReturn(600)
        whenever(mediaCarousel.scrollX).thenReturn(600)

        mediaCarousel.touchListener?.onTouchEvent(motionEventUp)
        executor.runAllReady()

        verify(mediaCarousel).smoothScrollTo(eq(carouselWidth), anyInt())
    }

    @Test
    fun testCarouselScroll_longScroll_isRTL() {
        whenever(mediaCarousel.isLayoutRtl).thenReturn(true)
        whenever(mediaCarousel.relativeScrollX).thenReturn(600)
        whenever(mediaCarousel.scrollX).thenReturn(carouselWidth - 600)

        mediaCarousel.touchListener?.onTouchEvent(motionEventUp)
        executor.runAllReady()

        verify(mediaCarousel).smoothScrollTo(eq(0), anyInt())
    }
}
