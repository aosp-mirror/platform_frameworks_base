/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import static android.view.RoundScrollbarRenderer.BLUECHIP_ENABLED_SYSPROP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.platform.test.annotations.Presubmit;
import android.view.flags.Flags;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link RoundScrollbarRenderer}.
 *
 * <p>Build/Install/Run: atest FrameworksCoreTests:android.view.RoundScrollbarRendererTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class RoundScrollbarRendererTest {

    private static final int DEFAULT_VERTICAL_SCROLL_RANGE = 100;
    private static final int DEFAULT_VERTICAL_SCROLL_EXTENT = 20;
    private static final int DEFAULT_VERTICAL_SCROLL_OFFSET = 40;
    private static final float DEFAULT_ALPHA = 0.5f;
    private static final Rect BOUNDS = new Rect(0, 0, 200, 200);

    @Mock private Canvas mCanvas;
    @Captor private ArgumentCaptor<Paint> mPaintCaptor;
    private RoundScrollbarRenderer mScrollbar;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        MockView view = spy(new MockView(ApplicationProvider.getApplicationContext()));
        when(view.canScrollVertically(anyInt())).thenReturn(true);
        when(view.computeVerticalScrollRange()).thenReturn(DEFAULT_VERTICAL_SCROLL_RANGE);
        when(view.computeVerticalScrollExtent()).thenReturn(DEFAULT_VERTICAL_SCROLL_EXTENT);
        when(view.computeVerticalScrollOffset()).thenReturn(DEFAULT_VERTICAL_SCROLL_OFFSET);
        mPaintCaptor = ArgumentCaptor.forClass(Paint.class);

        mScrollbar = new RoundScrollbarRenderer(view);
    }

    @Test
    public void testScrollbarDrawn_legacy() {
        assumeFalse(usingRefactoredScrollbar());
        mScrollbar.drawRoundScrollbars(mCanvas, DEFAULT_ALPHA, BOUNDS, /* drawToLeft= */ false);

        // The arc will be drawn twice, i.e. once for track and once for thumb
        verify(mCanvas, times(2))
                .drawArc(any(), anyFloat(), anyFloat(), eq(false), mPaintCaptor.capture());

        Paint thumbPaint = mPaintCaptor.getAllValues().getFirst();
        assertEquals(Paint.Cap.ROUND, thumbPaint.getStrokeCap());
        assertEquals(Paint.Style.STROKE, thumbPaint.getStyle());
        Paint trackPaint = mPaintCaptor.getAllValues().get(1);
        assertEquals(Paint.Cap.ROUND, trackPaint.getStrokeCap());
        assertEquals(Paint.Style.STROKE, trackPaint.getStyle());
    }

    @Test
    public void testScrollbarDrawn() {
        assumeTrue(usingRefactoredScrollbar());
        mScrollbar.drawRoundScrollbars(mCanvas, DEFAULT_ALPHA, BOUNDS, /* drawToLeft= */ false);

        // The arc will be drawn thrice, i.e. twice for track and once for thumb
        verify(mCanvas, times(3))
                .drawArc(any(), anyFloat(), anyFloat(), eq(false), mPaintCaptor.capture());

        // Verify paint styles
        Paint thumbPaint = mPaintCaptor.getAllValues().getFirst();
        assertEquals(Paint.Cap.ROUND, thumbPaint.getStrokeCap());
        assertEquals(Paint.Style.STROKE, thumbPaint.getStyle());
        Paint trackPaint = mPaintCaptor.getAllValues().get(1);
        assertEquals(Paint.Cap.ROUND, trackPaint.getStrokeCap());
        assertEquals(Paint.Style.STROKE, trackPaint.getStyle());
    }

    public static class MockView extends View {

        public MockView(Context context) {
            super(context);
        }

        @Override
        public int computeVerticalScrollRange() {
            return super.getHeight();
        }

        @Override
        public int computeVerticalScrollOffset() {
            return super.computeVerticalScrollOffset();
        }

        @Override
        public int computeVerticalScrollExtent() {
            return super.computeVerticalScrollExtent();
        }
    }

    private static boolean usingRefactoredScrollbar() {
        return Flags.useRefactoredRoundScrollbar()
                && SystemProperties.getBoolean(BLUECHIP_ENABLED_SYSPROP, false);
    }
}
