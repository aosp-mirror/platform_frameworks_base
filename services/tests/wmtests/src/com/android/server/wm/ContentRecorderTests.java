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

package com.android.server.wm;


import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
import static android.view.ContentRecordingSession.RECORD_CONTENT_TASK;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;
import android.view.ContentRecordingSession;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ContentRecorder} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ContentRecorderTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ContentRecorderTests extends WindowTestsBase {
    private static final IBinder TEST_TOKEN = new RecordingTestToken();
    private final ContentRecordingSession mDefaultSession =
            ContentRecordingSession.createDisplaySession(TEST_TOKEN);
    private static Point sSurfaceSize;
    private ContentRecorder mContentRecorder;
    private SurfaceControl mRecordedSurface;

    @Before public void setUp() {
        // GIVEN MediaProjection has already initialized the WindowToken of the DisplayArea to
        // mirror.
        setUpDefaultTaskDisplayAreaWindowToken();

        // GIVEN SurfaceControl can successfully mirror the provided surface.
        sSurfaceSize = new Point(
                mDefaultDisplay.getDefaultTaskDisplayArea().getBounds().width(),
                mDefaultDisplay.getDefaultTaskDisplayArea().getBounds().height());
        mRecordedSurface = surfaceControlMirrors(sSurfaceSize);

        // GIVEN the VirtualDisplay associated with the session (so the display has state ON).
        VirtualDisplay virtualDisplay = mWm.mDisplayManager.createVirtualDisplay("VirtualDisplay",
                sSurfaceSize.x, sSurfaceSize.y,
                DisplayMetrics.DENSITY_140, new Surface(), VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
        final int displayId = virtualDisplay.getDisplay().getDisplayId();
        mDefaultSession.setDisplayId(displayId);

        mWm.mRoot.onDisplayAdded(displayId);
        final DisplayContent mVirtualDisplayContent = mWm.mRoot.getDisplayContent(displayId);
        mContentRecorder = new ContentRecorder(mVirtualDisplayContent);
        spyOn(mVirtualDisplayContent);
    }

    @Test
    public void testIsCurrentlyRecording() {
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();

        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_display() {
        mContentRecorder.setContentRecordingSession(mDefaultSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testUpdateRecording_task() {
        mDefaultSession.setContentToRecord(RECORD_CONTENT_TASK);
        mContentRecorder.setContentRecordingSession(mDefaultSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_wasPaused() {
        mContentRecorder.setContentRecordingSession(mDefaultSession);
        mContentRecorder.updateRecording();

        mContentRecorder.pauseRecording();
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testUpdateRecording_wasStopped() {
        mContentRecorder.setContentRecordingSession(mDefaultSession);
        mContentRecorder.updateRecording();

        mContentRecorder.remove();
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testOnConfigurationChanged_neverRecording() {
        mContentRecorder.onConfigurationChanged(ORIENTATION_PORTRAIT);

        verify(mTransaction, never()).setPosition(eq(mRecordedSurface), anyFloat(), anyFloat());
        verify(mTransaction, never()).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    @Test
    public void testOnConfigurationChanged_resizesSurface() {
        mContentRecorder.setContentRecordingSession(mDefaultSession);
        mContentRecorder.updateRecording();
        mContentRecorder.onConfigurationChanged(ORIENTATION_PORTRAIT);

        verify(mTransaction, atLeastOnce()).setPosition(eq(mRecordedSurface), anyFloat(),
                anyFloat());
        verify(mTransaction, atLeastOnce()).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    @Test
    public void testPauseRecording_pausesRecording() {
        mContentRecorder.setContentRecordingSession(mDefaultSession);
        mContentRecorder.updateRecording();

        mContentRecorder.pauseRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testPauseRecording_neverRecording() {
        mContentRecorder.pauseRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testRemove_stopsRecording() {
        mContentRecorder.setContentRecordingSession(mDefaultSession);
        mContentRecorder.updateRecording();

        mContentRecorder.remove();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testRemove_neverRecording() {
        mContentRecorder.remove();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateMirroredSurface_capturedAreaResized() {
        mContentRecorder.setContentRecordingSession(mDefaultSession);
        mContentRecorder.updateRecording();

        // WHEN attempting to mirror on the virtual display, and the captured content is resized.
        float xScale = 0.7f;
        float yScale = 2f;
        Rect displayAreaBounds = new Rect(0, 0, Math.round(sSurfaceSize.x * xScale),
                Math.round(sSurfaceSize.y * yScale));
        mContentRecorder.updateMirroredSurface(mTransaction, displayAreaBounds, sSurfaceSize);

        // THEN content in the captured DisplayArea is scaled to fit the surface size.
        verify(mTransaction, atLeastOnce()).setMatrix(mRecordedSurface, 1.0f / yScale, 0, 0,
                1.0f / yScale);
        // THEN captured content is positioned in the centre of the output surface.
        float scaledWidth = displayAreaBounds.width() / xScale;
        float xInset = (sSurfaceSize.x - scaledWidth) / 2;
        verify(mTransaction, atLeastOnce()).setPosition(mRecordedSurface, xInset, 0);
    }

    private static class RecordingTestToken extends Binder {
    }

    /**
     * Creates a WindowToken associated with the default task DisplayArea, in order for that
     * DisplayArea to be mirrored.
     */
    private void setUpDefaultTaskDisplayAreaWindowToken() {
        // GIVEN the default task display area is represented by the WindowToken.
        spyOn(mWm.mWindowContextListenerController);
        doReturn(mDefaultDisplay.getDefaultTaskDisplayArea()).when(
                mWm.mWindowContextListenerController).getContainer(any());
    }

    /**
     * SurfaceControl successfully creates a mirrored surface of the given size.
     */
    private SurfaceControl surfaceControlMirrors(Point surfaceSize) {
        // Do not set the parent, since the mirrored surface is the root of a new surface hierarchy.
        SurfaceControl mirroredSurface = new SurfaceControl.Builder()
                .setName("mirroredSurface")
                .setBufferSize(surfaceSize.x, surfaceSize.y)
                .setCallsite("mirrorSurface")
                .build();
        doReturn(mirroredSurface).when(() -> SurfaceControl.mirrorSurface(any()));
        doReturn(surfaceSize).when(mWm.mDisplayManagerInternal).getDisplaySurfaceDefaultSize(
                anyInt());
        return mirroredSurface;
    }
}
