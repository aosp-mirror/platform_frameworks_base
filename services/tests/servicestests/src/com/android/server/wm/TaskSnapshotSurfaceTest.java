/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link TaskSnapshotSurface}.
 *
 * runtest frameworks-services -c com.android.server.wm.TaskSnapshotSurfaceTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskSnapshotSurfaceTest extends WindowTestsBase {

    private TaskSnapshotSurface mSurface;

    @Before
    public void setUp() {
        mSurface = new TaskSnapshotSurface(null, null, null, Color.WHITE);
    }

    @Test
    public void fillEmptyBackground_fillHorizontally() throws Exception {
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(200);
        when(mockCanvas.getHeight()).thenReturn(100);
        final Bitmap b = Bitmap.createBitmap(100, 200, Config.ARGB_8888);
        mSurface.fillEmptyBackground(mockCanvas, b);
        verify(mockCanvas).drawRect(eq(100.0f), eq(0.0f), eq(200.0f), eq(100.0f), any());
    }

    @Test
    public void fillEmptyBackground_fillVertically() throws Exception {
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(200);
        final Bitmap b = Bitmap.createBitmap(200, 100, Config.ARGB_8888);
        mSurface.fillEmptyBackground(mockCanvas, b);
        verify(mockCanvas).drawRect(eq(0.0f), eq(100.0f), eq(100.0f), eq(200.0f), any());
    }

    @Test
    public void fillEmptyBackground_fillBoth() throws Exception {
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(200);
        when(mockCanvas.getHeight()).thenReturn(200);
        final Bitmap b = Bitmap.createBitmap(100, 100, Config.ARGB_8888);
        mSurface.fillEmptyBackground(mockCanvas, b);
        verify(mockCanvas).drawRect(eq(100.0f), eq(0.0f), eq(200.0f), eq(100.0f), any());
        verify(mockCanvas).drawRect(eq(0.0f), eq(100.0f), eq(200.0f), eq(200.0f), any());
    }

    @Test
    public void fillEmptyBackground_dontFill_sameSize() throws Exception {
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        final Bitmap b = Bitmap.createBitmap(100, 100, Config.ARGB_8888);
        mSurface.fillEmptyBackground(mockCanvas, b);
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    public void fillEmptyBackground_dontFill_bitmapLarger() throws Exception {
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        final Bitmap b = Bitmap.createBitmap(200, 200, Config.ARGB_8888);
        mSurface.fillEmptyBackground(mockCanvas, b);
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }
}
