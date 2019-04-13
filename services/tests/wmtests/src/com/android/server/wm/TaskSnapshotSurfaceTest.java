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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;

import android.app.ActivityManager.TaskDescription;
import android.app.ActivityManager.TaskSnapshot;
import android.content.ComponentName;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.server.wm.TaskSnapshotSurface.Window;

import org.junit.Test;

/**
 * Test class for {@link TaskSnapshotSurface}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:TaskSnapshotSurfaceTest
 */
@SmallTest
@Presubmit
public class TaskSnapshotSurfaceTest extends WindowTestsBase {

    private TaskSnapshotSurface mSurface;

    private void setupSurface(int width, int height, Rect contentInsets, int sysuiVis,
            int windowFlags, Rect taskBounds) {
        final GraphicBuffer buffer = GraphicBuffer.create(width, height, PixelFormat.RGBA_8888,
                GraphicBuffer.USAGE_SW_READ_RARELY | GraphicBuffer.USAGE_SW_WRITE_NEVER);
        final TaskSnapshot snapshot = new TaskSnapshot(new ComponentName("", ""), buffer,
                ColorSpace.get(ColorSpace.Named.SRGB), ORIENTATION_PORTRAIT, contentInsets, false,
                1.0f, true /* isRealSnapshot */, WINDOWING_MODE_FULLSCREEN,
                0 /* systemUiVisibility */, false /* isTranslucent */);
        mSurface = new TaskSnapshotSurface(mWm, new Window(), new SurfaceControl(), snapshot, "Test",
                createTaskDescription(Color.WHITE, Color.RED, Color.BLUE), sysuiVis, windowFlags, 0,
                taskBounds, ORIENTATION_PORTRAIT);
    }

    private static TaskDescription createTaskDescription(int background, int statusBar,
            int navigationBar) {
        final TaskDescription td = new TaskDescription();
        td.setBackgroundColor(background);
        td.setStatusBarColor(statusBar);
        td.setNavigationBarColor(navigationBar);
        return td;
    }

    private void setupSurface(int width, int height) {
        setupSurface(width, height, new Rect(), 0, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, width, height));
    }

    @Test
    public void fillEmptyBackground_fillHorizontally() {
        setupSurface(200, 100);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(200);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 100, 200));
        verify(mockCanvas).drawRect(eq(100.0f), eq(0.0f), eq(200.0f), eq(100.0f), any());
    }

    @Test
    public void fillEmptyBackground_fillVertically() {
        setupSurface(100, 200);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(200);
        mSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 200, 100));
        verify(mockCanvas).drawRect(eq(0.0f), eq(100.0f), eq(100.0f), eq(200.0f), any());
    }

    @Test
    public void fillEmptyBackground_fillBoth() {
        setupSurface(200, 200);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(200);
        when(mockCanvas.getHeight()).thenReturn(200);
        mSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 100, 100));
        verify(mockCanvas).drawRect(eq(100.0f), eq(0.0f), eq(200.0f), eq(100.0f), any());
        verify(mockCanvas).drawRect(eq(0.0f), eq(100.0f), eq(200.0f), eq(200.0f), any());
    }

    @Test
    public void fillEmptyBackground_dontFill_sameSize() {
        setupSurface(100, 100);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 100, 100));
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    public void fillEmptyBackground_dontFill_bitmapLarger() {
        setupSurface(100, 100);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 200, 200));
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    public void testCalculateSnapshotCrop() {
        setupSurface(100, 100, new Rect(0, 10, 0, 10), 0, 0, new Rect(0, 0, 100, 100));
        assertEquals(new Rect(0, 0, 100, 90), mSurface.calculateSnapshotCrop());
    }

    @Test
    public void testCalculateSnapshotCrop_taskNotOnTop() {
        setupSurface(100, 100, new Rect(0, 10, 0, 10), 0, 0, new Rect(0, 50, 100, 100));
        assertEquals(new Rect(0, 10, 100, 90), mSurface.calculateSnapshotCrop());
    }

    @Test
    public void testCalculateSnapshotCrop_navBarLeft() {
        setupSurface(100, 100, new Rect(10, 10, 0, 0), 0, 0, new Rect(0, 0, 100, 100));
        assertEquals(new Rect(10, 0, 100, 100), mSurface.calculateSnapshotCrop());
    }

    @Test
    public void testCalculateSnapshotCrop_navBarRight() {
        setupSurface(100, 100, new Rect(0, 10, 10, 0), 0, 0, new Rect(0, 0, 100, 100));
        assertEquals(new Rect(0, 0, 90, 100), mSurface.calculateSnapshotCrop());
    }

    @Test
    public void testCalculateSnapshotFrame() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 0, 10);
        mSurface.setFrames(new Rect(0, 0, 100, 100), insets, insets);
        assertEquals(new Rect(0, -10, 100, 70),
                mSurface.calculateSnapshotFrame(new Rect(0, 10, 100, 90)));
    }

    @Test
    public void testCalculateSnapshotFrame_navBarLeft() {
        setupSurface(100, 100);
        final Rect insets = new Rect(10, 10, 0, 0);
        mSurface.setFrames(new Rect(0, 0, 100, 100), insets, insets);
        assertEquals(new Rect(0, -10, 90, 80),
                mSurface.calculateSnapshotFrame(new Rect(10, 10, 100, 100)));
    }

    @Test
    public void testDrawStatusBarBackground() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSurface.setFrames(new Rect(0, 0, 100, 100), insets, insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.mSystemBarBackgroundPainter.drawStatusBarBackground(
                mockCanvas, new Rect(0, 0, 50, 100), 10);
        verify(mockCanvas).drawRect(eq(50.0f), eq(0.0f), eq(90.0f), eq(10.0f), any());
    }

    @Test
    public void testDrawStatusBarBackground_nullFrame() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSurface.setFrames(new Rect(0, 0, 100, 100), insets, insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.mSystemBarBackgroundPainter.drawStatusBarBackground(
                mockCanvas, null, 10);
        verify(mockCanvas).drawRect(eq(0.0f), eq(0.0f), eq(90.0f), eq(10.0f), any());
    }

    @Test
    public void testDrawStatusBarBackground_nope() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSurface.setFrames(new Rect(0, 0, 100, 100), insets, insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.mSystemBarBackgroundPainter.drawStatusBarBackground(
                mockCanvas, new Rect(0, 0, 100, 100), 10);
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    public void testDrawNavigationBarBackground() {
        final Rect insets = new Rect(0, 10, 0, 10);
        setupSurface(100, 100, insets, 0, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSurface.setFrames(new Rect(0, 0, 100, 100), insets, insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.mSystemBarBackgroundPainter.drawNavigationBarBackground(mockCanvas);
        verify(mockCanvas).drawRect(eq(new Rect(0, 90, 100, 100)), any());
    }

    @Test
    public void testDrawNavigationBarBackground_left() {
        final Rect insets = new Rect(10, 10, 0, 0);
        setupSurface(100, 100, insets, 0, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSurface.setFrames(new Rect(0, 0, 100, 100), insets, insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.mSystemBarBackgroundPainter.drawNavigationBarBackground(mockCanvas);
        verify(mockCanvas).drawRect(eq(new Rect(0, 0, 10, 100)), any());
    }

    @Test
    public void testDrawNavigationBarBackground_right() {
        final Rect insets = new Rect(0, 10, 10, 0);
        setupSurface(100, 100, insets, 0, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSurface.setFrames(new Rect(0, 0, 100, 100), insets, insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSurface.mSystemBarBackgroundPainter.drawNavigationBarBackground(mockCanvas);
        verify(mockCanvas).drawRect(eq(new Rect(90, 0, 100, 100)), any());
    }
}
