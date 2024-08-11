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

package android.window;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager.TaskDescription;
import android.content.ComponentName;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsets;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link SnapshotDrawerUtils}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SnapshotDrawerUtilsTest {

    private SnapshotDrawerUtils.SnapshotSurface mSnapshotSurface;

    private void setupSurface(int width, int height) {
        setupSurface(width, height, new Rect(), FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, width, height));
    }

    private void setupSurface(int width, int height, Rect contentInsets,
            int windowFlags, Rect taskBounds) {
        // Previously when constructing TaskSnapshots for this test, scale was 1.0f, so to mimic
        // this behavior set the taskSize to be the same as the taskBounds width and height. The
        // taskBounds passed here are assumed to be the same task bounds as when the snapshot was
        // taken. We assume there is no aspect ratio mismatch between the screenshot and the
        // taskBounds
        assertEquals(width, taskBounds.width());
        assertEquals(height, taskBounds.height());
        Point taskSize = new Point(taskBounds.width(), taskBounds.height());

        final TaskSnapshot snapshot = createTaskSnapshot(width, height, taskSize, contentInsets);
        TaskDescription taskDescription = createTaskDescription(Color.WHITE,
                Color.RED, Color.BLUE);

        mSnapshotSurface = new SnapshotDrawerUtils.SnapshotSurface(
                new SurfaceControl(), snapshot, "Test");
        mSnapshotSurface.initiateSystemBarPainter(windowFlags, 0, 0,
                taskDescription, WindowInsets.Type.defaultVisible());
    }

    private TaskSnapshot createTaskSnapshot(int width, int height, Point taskSize,
            Rect contentInsets) {
        final HardwareBuffer buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888,
                1, HardwareBuffer.USAGE_CPU_READ_RARELY);
        return new TaskSnapshot(
                System.currentTimeMillis(),
                0 /* captureTime */,
                new ComponentName("", ""), buffer,
                ColorSpace.get(ColorSpace.Named.SRGB), ORIENTATION_PORTRAIT,
                Surface.ROTATION_0, taskSize, contentInsets, new Rect() /* letterboxInsets */,
                false, true /* isRealSnapshot */, WINDOWING_MODE_FULLSCREEN,
                0 /* systemUiVisibility */, false /* isTranslucent */, false /* hasImeSurface */,
                0 /* uiMode */);
    }

    private static TaskDescription createTaskDescription(int background,
            int statusBar, int navigationBar) {
        final TaskDescription td = new TaskDescription();
        td.setBackgroundColor(background);
        td.setStatusBarColor(statusBar);
        td.setNavigationBarColor(navigationBar);
        return td;
    }

    @Test
    public void fillEmptyBackground_fillHorizontally() {
        setupSurface(200, 100);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(200);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 100, 200));
        verify(mockCanvas).drawRect(eq(100.0f), eq(0.0f), eq(200.0f), eq(100.0f), any());
    }

    @Test
    public void fillEmptyBackground_fillVertically() {
        setupSurface(100, 200);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(200);
        mSnapshotSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 200, 100));
        verify(mockCanvas).drawRect(eq(0.0f), eq(100.0f), eq(100.0f), eq(200.0f), any());
    }

    @Test
    public void fillEmptyBackground_fillBoth() {
        setupSurface(200, 200);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(200);
        when(mockCanvas.getHeight()).thenReturn(200);
        mSnapshotSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 100, 100));
        verify(mockCanvas).drawRect(eq(100.0f), eq(0.0f), eq(200.0f), eq(100.0f), any());
        verify(mockCanvas).drawRect(eq(0.0f), eq(100.0f), eq(200.0f), eq(200.0f), any());
    }

    @Test
    public void fillEmptyBackground_dontFill_sameSize() {
        setupSurface(100, 100);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 100, 100));
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    public void fillEmptyBackground_dontFill_bitmapLarger() {
        setupSurface(100, 100);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawBackgroundAndBars(mockCanvas, new Rect(0, 0, 200, 200));
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    public void testCalculateSnapshotCrop() {
        final Rect contentInsets = new Rect(0, 10, 0, 10);
        setupSurface(100, 100, contentInsets, 0, new Rect(0, 0, 100, 100));
        assertEquals(new Rect(0, 0, 100, 90),
                mSnapshotSurface.calculateSnapshotCrop(contentInsets));
    }

    @Test
    public void testCalculateSnapshotCrop_taskNotOnTop() {
        final Rect contentInsets = new Rect(0, 10, 0, 10);
        final Rect bounds = new Rect(0, 50, 100, 150);
        setupSurface(100, 100, contentInsets, 0, bounds);
        mSnapshotSurface.setFrames(bounds, contentInsets);
        assertEquals(new Rect(0, 10, 100, 90),
                mSnapshotSurface.calculateSnapshotCrop(contentInsets));
    }

    @Test
    public void testCalculateSnapshotCrop_navBarLeft() {
        final Rect contentInsets = new Rect(10, 0, 0, 0);
        setupSurface(100, 100, contentInsets, 0, new Rect(0, 0, 100, 100));
        assertEquals(new Rect(10, 0, 100, 100),
                mSnapshotSurface.calculateSnapshotCrop(contentInsets));
    }

    @Test
    public void testCalculateSnapshotCrop_navBarRight() {
        final Rect contentInsets = new Rect(0, 10, 10, 0);
        setupSurface(100, 100, contentInsets, 0, new Rect(0, 0, 100, 100));
        assertEquals(new Rect(0, 0, 90, 100),
                mSnapshotSurface.calculateSnapshotCrop(contentInsets));
    }

    @Test
    public void testCalculateSnapshotCrop_waterfall() {
        final Rect contentInsets = new Rect(5, 10, 5, 10);
        setupSurface(100, 100, contentInsets, 0, new Rect(0, 0, 100, 100));
        assertEquals(new Rect(5, 0, 95, 90),
                mSnapshotSurface.calculateSnapshotCrop(contentInsets));
    }

    @Test
    public void testCalculateSnapshotFrame() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 0, 10);
        mSnapshotSurface.setFrames(new Rect(0, 0, 100, 100), insets);
        assertEquals(new Rect(0, 0, 100, 80),
                mSnapshotSurface.calculateSnapshotFrame(new Rect(0, 10, 100, 90)));
    }

    @Test
    public void testCalculateSnapshotFrame_navBarLeft() {
        setupSurface(100, 100);
        final Rect insets = new Rect(10, 10, 0, 0);
        mSnapshotSurface.setFrames(new Rect(0, 0, 100, 100), insets);
        assertEquals(new Rect(10, 0, 100, 90),
                mSnapshotSurface.calculateSnapshotFrame(new Rect(10, 10, 100, 100)));
    }

    @Test
    public void testCalculateSnapshotFrame_waterfall() {
        setupSurface(100, 100, new Rect(5, 10, 5, 10), 0, new Rect(0, 0, 100, 100));
        final Rect insets = new Rect(0, 10, 0, 10);
        mSnapshotSurface.setFrames(new Rect(5, 0, 95, 100), insets);
        assertEquals(new Rect(0, 0, 90, 90),
                mSnapshotSurface.calculateSnapshotFrame(new Rect(5, 0, 95, 90)));
    }

    @Test
    public void testDrawStatusBarBackground() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSnapshotSurface.setFrames(new Rect(0, 0, 100, 100), insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawStatusBarBackground(mockCanvas, new Rect(0, 0, 50, 100));
        verify(mockCanvas).drawRect(eq(50.0f), eq(0.0f), eq(90.0f), eq(10.0f), any());
    }

    @Test
    public void testDrawStatusBarBackground_nullFrame() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSnapshotSurface.setFrames(new Rect(0, 0, 100, 100), insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawStatusBarBackground(mockCanvas, null);
        verify(mockCanvas).drawRect(eq(0.0f), eq(0.0f), eq(90.0f), eq(10.0f), any());
    }

    @Test
    public void testDrawStatusBarBackground_nope() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSnapshotSurface.setFrames(new Rect(0, 0, 100, 100), insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawStatusBarBackground(mockCanvas, new Rect(0, 0, 100, 100));
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    public void testDrawNavigationBarBackground() {
        final Rect insets = new Rect(0, 10, 0, 10);
        setupSurface(100, 100, insets, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSnapshotSurface.setFrames(new Rect(0, 0, 100, 100), insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawNavigationBarBackground(mockCanvas);
        verify(mockCanvas).drawRect(eq(new Rect(0, 90, 100, 100)), any());
    }

    @Test
    public void testDrawNavigationBarBackground_left() {
        final Rect insets = new Rect(10, 10, 0, 0);
        setupSurface(100, 100, insets, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSnapshotSurface.setFrames(new Rect(0, 0, 100, 100), insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawNavigationBarBackground(mockCanvas);
        verify(mockCanvas).drawRect(eq(new Rect(0, 0, 10, 100)), any());
    }

    @Test
    public void testDrawNavigationBarBackground_right() {
        final Rect insets = new Rect(0, 10, 10, 0);
        setupSurface(100, 100, insets, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSnapshotSurface.setFrames(new Rect(0, 0, 100, 100), insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSnapshotSurface.drawNavigationBarBackground(mockCanvas);
        verify(mockCanvas).drawRect(eq(new Rect(90, 0, 100, 100)), any());
    }
}
