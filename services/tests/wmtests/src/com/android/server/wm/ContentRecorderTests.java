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
import static android.view.Display.INVALID_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ContentRecorder.KEY_RECORD_TASK_FEATURE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.util.DisplayMetrics;
import android.view.ContentRecordingSession;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.server.wm.ContentRecorder.MediaProjectionManagerWrapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;

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
    private static IBinder sTaskWindowContainerToken;
    private Task mTask;
    private final ContentRecordingSession mDisplaySession =
            ContentRecordingSession.createDisplaySession(TEST_TOKEN);
    private ContentRecordingSession mTaskSession;
    private static Point sSurfaceSize;
    private ContentRecorder mContentRecorder;
    @Mock private MediaProjectionManagerWrapper mMediaProjectionManagerWrapper;
    private SurfaceControl mRecordedSurface;
    // Handle feature flag.
    private ConfigListener mConfigListener;
    private CountDownLatch mLatch;

    private VirtualDisplay mVirtualDisplay;

    @Before public void setUp() {
        MockitoAnnotations.initMocks(this);

        // GIVEN SurfaceControl can successfully mirror the provided surface.
        sSurfaceSize = new Point(
                mDefaultDisplay.getDefaultTaskDisplayArea().getBounds().width(),
                mDefaultDisplay.getDefaultTaskDisplayArea().getBounds().height());
        mRecordedSurface = surfaceControlMirrors(sSurfaceSize);

        doReturn(INVALID_DISPLAY).when(mWm.mDisplayManagerInternal).getDisplayIdToMirror(anyInt());

        // GIVEN the VirtualDisplay associated with the session (so the display has state ON).
        mVirtualDisplay = mWm.mDisplayManager.createVirtualDisplay("VirtualDisplay",
                sSurfaceSize.x, sSurfaceSize.y,
                DisplayMetrics.DENSITY_140, new Surface(), VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
        final int displayId = mVirtualDisplay.getDisplay().getDisplayId();
        mWm.mRoot.onDisplayAdded(displayId);
        final DisplayContent virtualDisplayContent = mWm.mRoot.getDisplayContent(displayId);
        mContentRecorder = new ContentRecorder(virtualDisplayContent,
                mMediaProjectionManagerWrapper);
        spyOn(virtualDisplayContent);

        // GIVEN MediaProjection has already initialized the WindowToken of the DisplayArea to
        // record.
        setUpDefaultTaskDisplayAreaWindowToken();
        mDisplaySession.setDisplayId(displayId);

        // GIVEN there is a window token associated with a task to record.
        sTaskWindowContainerToken = setUpTaskWindowContainerToken(virtualDisplayContent);
        mTaskSession = ContentRecordingSession.createTaskSession(sTaskWindowContainerToken);
        mTaskSession.setDisplayId(displayId);

        mConfigListener = new ConfigListener();
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                mContext.getMainExecutor(), mConfigListener);
        mLatch = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER, KEY_RECORD_TASK_FEATURE,
                "true", true);
    }

    @After
    public void teardown() {
        DeviceConfig.removeOnPropertiesChangedListener(mConfigListener);
        mVirtualDisplay.release();
        mWm.mRoot.onDisplayRemoved(mVirtualDisplay.getDisplay().getDisplayId());
    }

    @Test
    public void testIsCurrentlyRecording() {
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();

        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_display() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testUpdateRecording_display_nullToken() {
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(TEST_TOKEN);
        session.setDisplayId(mDisplaySession.getDisplayId());
        session.setTokenToRecord(null);
        mContentRecorder.setContentRecordingSession(session);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_display_noWindowContainer() {
        doReturn(null).when(
                mWm.mWindowContextListenerController).getContainer(any());
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_task_featureDisabled() {
        mLatch = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER, KEY_RECORD_TASK_FEATURE,
                "false", false);
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_task_featureEnabled() {
        // Feature already enabled; don't need to again.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testUpdateRecording_task_nullToken() {
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(
                sTaskWindowContainerToken);
        session.setDisplayId(mDisplaySession.getDisplayId());
        session.setTokenToRecord(null);
        mContentRecorder.setContentRecordingSession(session);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
        verify(mMediaProjectionManagerWrapper).stopActiveProjection();
    }

    @Test
    public void testUpdateRecording_task_noWindowContainer() {
        // Use the window container token of the DisplayContent, rather than task.
        ContentRecordingSession invalidTaskSession = ContentRecordingSession.createTaskSession(
                new WindowContainer.RemoteToken(mDisplayContent));
        mContentRecorder.setContentRecordingSession(invalidTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
        verify(mMediaProjectionManagerWrapper).stopActiveProjection();
    }

    @Test
    public void testUpdateRecording_wasPaused() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();

        mContentRecorder.pauseRecording();
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
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
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        mContentRecorder.onConfigurationChanged(ORIENTATION_PORTRAIT);

        verify(mTransaction, atLeast(2)).setPosition(eq(mRecordedSurface), anyFloat(),
                anyFloat());
        verify(mTransaction, atLeast(2)).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    @Test
    public void testOnTaskOrientationConfigurationChanged_resizesSurface() {
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();

        Configuration config = mTask.getConfiguration();
        config.orientation = ORIENTATION_PORTRAIT;
        mTask.onConfigurationChanged(config);

        verify(mTransaction, atLeast(2)).setPosition(eq(mRecordedSurface), anyFloat(),
                anyFloat());
        verify(mTransaction, atLeast(2)).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    @Test
    public void testOnTaskBoundsConfigurationChanged_notifiesCallback() {
        final int recordedWidth = 333;
        final int recordedHeight = 999;
        // WHEN a recording is ongoing.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // WHEN a configuration change arrives, and the recorded content is a different size.
        mTask.setBounds(new Rect(0, 0, recordedWidth, recordedHeight));
        mContentRecorder.onConfigurationChanged(mDefaultDisplay.getLastOrientation());
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // THEN content in the captured DisplayArea is scaled to fit the surface size.
        verify(mTransaction, atLeastOnce()).setMatrix(eq(mRecordedSurface), anyFloat(), eq(0f),
                eq(0f),
                anyFloat());
        // THEN the resize callback is notified.
        verify(mMediaProjectionManagerWrapper).notifyActiveProjectionCapturedContentResized(
                recordedWidth, recordedHeight);
    }

    @Test
    public void testStartRecording_notifiesCallback_taskSession() {
        // WHEN a recording is ongoing.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // THEN the visibility change callback is notified.
        verify(mMediaProjectionManagerWrapper)
                .notifyActiveProjectionCapturedContentVisibilityChanged(true);
    }

    @Test
    public void testStartRecording_notifiesCallback_displaySession() {
        // WHEN a recording is ongoing.
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // THEN the visibility change callback is notified.
        verify(mMediaProjectionManagerWrapper)
                .notifyActiveProjectionCapturedContentVisibilityChanged(true);
    }

    @Test
    public void testOnVisibleRequestedChanged_notifiesCallback() {
        // WHEN a recording is ongoing.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // WHEN the child requests a visibility change.
        boolean isVisibleRequested = true;
        mContentRecorder.onVisibleRequestedChanged(isVisibleRequested);

        // THEN the visibility change callback is notified.
        verify(mMediaProjectionManagerWrapper, atLeastOnce())
                .notifyActiveProjectionCapturedContentVisibilityChanged(isVisibleRequested);

        // WHEN the child requests a visibility change.
        isVisibleRequested = false;
        mContentRecorder.onVisibleRequestedChanged(isVisibleRequested);

        // THEN the visibility change callback is notified.
        verify(mMediaProjectionManagerWrapper)
                .notifyActiveProjectionCapturedContentVisibilityChanged(isVisibleRequested);
    }

    @Test
    public void testOnVisibleRequestedChanged_noRecording_doesNotNotifyCallback() {
        // WHEN a recording is not ongoing.
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();

        // WHEN the child requests a visibility change.
        boolean isVisibleRequested = true;
        mContentRecorder.onVisibleRequestedChanged(isVisibleRequested);

        // THEN the visibility change callback is not notified.
        verify(mMediaProjectionManagerWrapper, never())
                .notifyActiveProjectionCapturedContentVisibilityChanged(isVisibleRequested);

        // WHEN the child requests a visibility change.
        isVisibleRequested = false;
        mContentRecorder.onVisibleRequestedChanged(isVisibleRequested);

        // THEN the visibility change callback is not notified.
        verify(mMediaProjectionManagerWrapper, never())
                .notifyActiveProjectionCapturedContentVisibilityChanged(isVisibleRequested);
    }

    @Test
    public void testPauseRecording_pausesRecording() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
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
    public void testStopRecording_stopsRecording() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();

        mContentRecorder.stopRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testStopRecording_neverRecording() {
        mContentRecorder.stopRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testRemoveTask_stopsRecording() {
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();

        mTask.removeImmediately();

        verify(mMediaProjectionManagerWrapper).stopActiveProjection();
    }

    @Test
    public void testRemoveTask_stopsRecording_nullSessionShouldNotThrowExceptions() {
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        mContentRecorder.setContentRecordingSession(null);
        mTask.removeImmediately();
    }

    @Test
    public void testUpdateMirroredSurface_capturedAreaResized() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // WHEN attempting to mirror on the virtual display, and the captured content is resized.
        float xScale = 0.7f;
        float yScale = 2f;
        Rect displayAreaBounds = new Rect(0, 0, Math.round(sSurfaceSize.x * xScale),
                Math.round(sSurfaceSize.y * yScale));
        mContentRecorder.updateMirroredSurface(mTransaction, displayAreaBounds, sSurfaceSize);
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // THEN content in the captured DisplayArea is scaled to fit the surface size.
        verify(mTransaction, atLeastOnce()).setMatrix(mRecordedSurface, 1.0f / yScale, 0, 0,
                1.0f / yScale);
        // THEN captured content is positioned in the centre of the output surface.
        int scaledWidth = Math.round((float) displayAreaBounds.width() / xScale);
        int xInset = (sSurfaceSize.x - scaledWidth) / 2;
        verify(mTransaction, atLeastOnce()).setPosition(mRecordedSurface, xInset, 0);
        // THEN the resize callback is notified.
        verify(mMediaProjectionManagerWrapper).notifyActiveProjectionCapturedContentResized(
                displayAreaBounds.width(), displayAreaBounds.height());
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
     * Creates a {@link android.window.WindowContainerToken} associated with a task, in order for
     * that task to be recorded.
     */
    private IBinder setUpTaskWindowContainerToken(DisplayContent displayContent) {
        final Task rootTask = createTask(displayContent);
        mTask = createTaskInRootTask(rootTask, 0 /* userId */);
        // Ensure the task is not empty.
        createActivityRecord(displayContent, mTask);
        return mTask.getTaskInfo().token.asBinder();
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

    private class ConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        @Override
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            if (mLatch != null && properties.getKeyset().contains(KEY_RECORD_TASK_FEATURE)) {
                mLatch.countDown();
            }
        }
    }
}
