/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.TaskSnapshotController.SNAPSHOT_MODE_APP_THEME;
import static com.android.server.wm.TaskSnapshotController.SNAPSHOT_MODE_REAL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.window.TaskSnapshot;

import androidx.test.filters.SmallTest;

import com.google.android.collect.Sets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/**
 * Test class for {@link TaskSnapshotController}.
 *
 * Build/Install/Run:
 *  *  atest WmTests:TaskSnapshotControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskSnapshotControllerTest extends WindowTestsBase {

    @Test
    public void testGetClosingApps_closing() {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        closingWindow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        final ArraySet<ActivityRecord> closingApps = new ArraySet<>();
        closingApps.add(closingWindow.mActivityRecord);
        final ArraySet<Task> closingTasks = new ArraySet<>();
        mWm.mTaskSnapshotController.getClosingTasks(closingApps, closingTasks);
        assertEquals(1, closingTasks.size());
        assertEquals(closingWindow.mActivityRecord.getTask(), closingTasks.valueAt(0));
    }

    @Test
    public void testGetClosingApps_notClosing() {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        final WindowState openingWindow = createAppWindow(closingWindow.getTask(),
                FIRST_APPLICATION_WINDOW, "openingWindow");
        closingWindow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        openingWindow.mActivityRecord.commitVisibility(
                true /* visible */, true /* performLayout */);
        final ArraySet<ActivityRecord> closingApps = new ArraySet<>();
        closingApps.add(closingWindow.mActivityRecord);
        final ArraySet<Task> closingTasks = new ArraySet<>();
        mWm.mTaskSnapshotController.getClosingTasks(closingApps, closingTasks);
        assertEquals(0, closingTasks.size());
    }

    @Test
    public void testGetClosingApps_skipClosingAppsSnapshotTasks() {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        closingWindow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        final ArraySet<ActivityRecord> closingApps = new ArraySet<>();
        closingApps.add(closingWindow.mActivityRecord);
        final ArraySet<Task> closingTasks = new ArraySet<>();
        mWm.mTaskSnapshotController.addSkipClosingAppSnapshotTasks(
                Sets.newArraySet(closingWindow.mActivityRecord.getTask()));
        mWm.mTaskSnapshotController.getClosingTasks(closingApps, closingTasks);
        assertEquals(0, closingTasks.size());
    }

    @Test
    public void testGetSnapshotMode() {
        final WindowState disabledWindow = createWindow(null,
                FIRST_APPLICATION_WINDOW, mDisplayContent, "disabledWindow");
        disabledWindow.mActivityRecord.setDisablePreviewScreenshots(true);
        assertEquals(SNAPSHOT_MODE_APP_THEME,
                mWm.mTaskSnapshotController.getSnapshotMode(disabledWindow.getTask()));

        final WindowState normalWindow = createWindow(null,
                FIRST_APPLICATION_WINDOW, mDisplayContent, "normalWindow");
        assertEquals(SNAPSHOT_MODE_REAL,
                mWm.mTaskSnapshotController.getSnapshotMode(normalWindow.getTask()));

        final WindowState secureWindow = createWindow(null,
                FIRST_APPLICATION_WINDOW, mDisplayContent, "secureWindow");
        secureWindow.mAttrs.flags |= FLAG_SECURE;
        assertEquals(SNAPSHOT_MODE_APP_THEME,
                mWm.mTaskSnapshotController.getSnapshotMode(secureWindow.getTask()));
    }

    @Test
    public void testSnapshotBuilder() {
        final HardwareBuffer buffer = Mockito.mock(HardwareBuffer.class);
        final ColorSpace sRGB = ColorSpace.get(ColorSpace.Named.SRGB);
        final long id = 1234L;
        final ComponentName activityComponent = new ComponentName("package", ".Class");
        final int windowingMode = WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        final int appearance = APPEARANCE_LIGHT_STATUS_BARS;
        final int pixelFormat = PixelFormat.RGBA_8888;
        final int orientation = Configuration.ORIENTATION_PORTRAIT;
        final float scaleFraction = 0.25f;
        final Rect contentInsets = new Rect(1, 2, 3, 4);
        final Point taskSize = new Point(5, 6);

        try {
            TaskSnapshot.Builder builder =
                    new TaskSnapshot.Builder();
            builder.setId(id);
            builder.setTopActivityComponent(activityComponent);
            builder.setAppearance(appearance);
            builder.setWindowingMode(windowingMode);
            builder.setColorSpace(sRGB);
            builder.setOrientation(orientation);
            builder.setContentInsets(contentInsets);
            builder.setIsTranslucent(true);
            builder.setSnapshot(buffer);
            builder.setIsRealSnapshot(true);
            builder.setPixelFormat(pixelFormat);
            builder.setTaskSize(taskSize);

            // Not part of TaskSnapshot itself, used in screenshot process
            assertEquals(pixelFormat, builder.getPixelFormat());

            TaskSnapshot snapshot = builder.build();
            assertEquals(id, snapshot.getId());
            assertEquals(activityComponent, snapshot.getTopActivityComponent());
            assertEquals(appearance, snapshot.getAppearance());
            assertEquals(windowingMode, snapshot.getWindowingMode());
            assertEquals(sRGB, snapshot.getColorSpace());
            // Snapshots created with the Builder class are always high-res. The only way to get a
            // low-res snapshot is to load it from the disk in TaskSnapshotLoader.
            assertFalse(snapshot.isLowResolution());
            assertEquals(orientation, snapshot.getOrientation());
            assertEquals(contentInsets, snapshot.getContentInsets());
            assertTrue(snapshot.isTranslucent());
            assertSame(buffer, snapshot.getHardwareBuffer());
            assertTrue(snapshot.isRealSnapshot());
            assertEquals(taskSize, snapshot.getTaskSize());
        } finally {
            if (buffer != null) {
                buffer.close();
            }
        }
    }

    @UseTestDisplay(addWindows = {W_ACTIVITY, W_INPUT_METHOD})
    @Test
    public void testCreateTaskSnapshotWithExcludingIme() {
        Task task = mAppWindow.mActivityRecord.getTask();
        spyOn(task);
        spyOn(mDisplayContent);
        when(task.getDisplayContent().shouldImeAttachedToApp()).thenReturn(false);
        // Intentionally set the SurfaceControl of input method window as null.
        mDisplayContent.mInputMethodWindow.setSurfaceControl(null);
        // Verify no NPE happens when calling createTaskSnapshot.
        try {
            final TaskSnapshot.Builder builder = new TaskSnapshot.Builder();
            mWm.mTaskSnapshotController.createTaskSnapshot(mAppWindow.mActivityRecord.getTask(),
                    1f /* scaleFraction */, PixelFormat.UNKNOWN, null /* outTaskSize */, builder);
        } catch (NullPointerException e) {
            fail("There should be no exception when calling createTaskSnapshot");
        }
    }

    @UseTestDisplay(addWindows = {W_ACTIVITY, W_INPUT_METHOD})
    @Test
    public void testCreateTaskSnapshotWithIncludingIme() {
        Task task = mAppWindow.mActivityRecord.getTask();
        spyOn(task);
        spyOn(mDisplayContent);
        spyOn(mDisplayContent.mInputMethodWindow);
        when(task.getDisplayContent().shouldImeAttachedToApp()).thenReturn(true);
        // Intentionally set the IME window is in drawn state.
        doReturn(true).when(mDisplayContent.mInputMethodWindow).isDrawn();
        // Verify no NPE happens when calling createTaskSnapshot.
        try {
            final TaskSnapshot.Builder builder = new TaskSnapshot.Builder();
            spyOn(builder);
            mWm.mTaskSnapshotController.createTaskSnapshot(
                    mAppWindow.mActivityRecord.getTask(), 1f /* scaleFraction */,
                    PixelFormat.UNKNOWN, null /* outTaskSize */, builder);
            // Verify the builder should includes IME surface.
            verify(builder).setHasImeSurface(eq(true));
            builder.setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            builder.setTaskSize(new Point(100, 100));
            final TaskSnapshot snapshot = builder.build();
            assertTrue(snapshot.hasImeSurface());
        } catch (NullPointerException e) {
            fail("There should be no exception when calling createTaskSnapshot");
        }
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testPrepareTaskSnapshot() {
        mAppWindow.mWinAnimator.mLastAlpha = 1f;
        spyOn(mAppWindow.mWinAnimator);
        doReturn(true).when(mAppWindow.mWinAnimator).getShown();
        doReturn(true).when(mAppWindow.mActivityRecord).isSurfaceShowing();

        final TaskSnapshot.Builder builder =
                new TaskSnapshot.Builder();
        boolean success = mWm.mTaskSnapshotController.prepareTaskSnapshot(
                mAppWindow.mActivityRecord.getTask(), PixelFormat.UNKNOWN, builder);

        assertTrue(success);
        // The pixel format should be selected automatically.
        assertNotEquals(PixelFormat.UNKNOWN, builder.getPixelFormat());

        // Snapshot should not be taken while the rotation of activity and task are different.
        doReturn(true).when(mAppWindow.mActivityRecord).hasFixedRotationTransform();
        success = mWm.mTaskSnapshotController.prepareTaskSnapshot(
                mAppWindow.mActivityRecord.getTask(), PixelFormat.UNKNOWN, builder);

        assertFalse(success);
    }
}
