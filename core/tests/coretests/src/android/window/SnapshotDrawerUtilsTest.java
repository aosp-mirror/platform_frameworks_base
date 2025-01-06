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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
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

    private SnapshotDrawerUtils.SystemBarBackgroundPainter mSystemBarBackgroundPainter;

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

        TaskDescription taskDescription = createTaskDescription(Color.WHITE,
                Color.RED, Color.BLUE);

        mSystemBarBackgroundPainter = new SnapshotDrawerUtils.SystemBarBackgroundPainter(
                windowFlags, 0 /* windowPrivateFlags */, 0 /* appearance */,
                taskDescription, 1f /* scale */, WindowInsets.Type.defaultVisible());
        mSystemBarBackgroundPainter.setInsets(contentInsets);
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
    public void testDrawStatusBarBackground() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSystemBarBackgroundPainter.setInsets(insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSystemBarBackgroundPainter.drawDecors(mockCanvas, new Rect(0, 0, 50, 100));
        verify(mockCanvas).drawRect(eq(50.0f), eq(0.0f), eq(90.0f), eq(10.0f), any());
    }

    @Test
    public void testDrawStatusBarBackground_nullFrame() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSystemBarBackgroundPainter.setInsets(insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSystemBarBackgroundPainter.drawDecors(mockCanvas, null /* alreadyDrawnFrame */);
        verify(mockCanvas).drawRect(eq(0.0f), eq(0.0f), eq(90.0f), eq(10.0f), any());
    }

    @Test
    public void testDrawStatusBarBackground_nope() {
        setupSurface(100, 100);
        final Rect insets = new Rect(0, 10, 10, 0);
        mSystemBarBackgroundPainter.setInsets(insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSystemBarBackgroundPainter.drawDecors(mockCanvas, new Rect(0, 0, 100, 100));
        verify(mockCanvas, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    public void testDrawNavigationBarBackground() {
        final Rect insets = new Rect(0, 10, 0, 10);
        setupSurface(100, 100, insets, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSystemBarBackgroundPainter.setInsets(insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSystemBarBackgroundPainter.drawDecors(mockCanvas, null /* alreadyDrawnFrame */);
        verify(mockCanvas).drawRect(eq(new Rect(0, 90, 100, 100)), any());
    }

    @Test
    public void testDrawNavigationBarBackground_left() {
        final Rect insets = new Rect(10, 10, 0, 0);
        setupSurface(100, 100, insets, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSystemBarBackgroundPainter.setInsets(insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSystemBarBackgroundPainter.drawDecors(mockCanvas, null /* alreadyDrawnFrame */);
        verify(mockCanvas).drawRect(eq(new Rect(0, 0, 10, 100)), any());
    }

    @Test
    public void testDrawNavigationBarBackground_right() {
        final Rect insets = new Rect(0, 10, 10, 0);
        setupSurface(100, 100, insets, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                new Rect(0, 0, 100, 100));
        mSystemBarBackgroundPainter.setInsets(insets);
        final Canvas mockCanvas = mock(Canvas.class);
        when(mockCanvas.getWidth()).thenReturn(100);
        when(mockCanvas.getHeight()).thenReturn(100);
        mSystemBarBackgroundPainter.drawDecors(mockCanvas, null /* alreadyDrawnFrame */);
        verify(mockCanvas).drawRect(eq(new Rect(90, 0, 100, 100)), any());
    }
}
