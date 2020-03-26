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

import android.app.ActivityManager;
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
import android.view.View;

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
        final int systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN;
        final int pixelFormat = PixelFormat.RGBA_8888;
        final int orientation = Configuration.ORIENTATION_PORTRAIT;
        final float scaleFraction = 0.25f;
        final Rect contentInsets = new Rect(1, 2, 3, 4);
        final Point taskSize = new Point(5, 6);

        try {
            ActivityManager.TaskSnapshot.Builder builder =
                    new ActivityManager.TaskSnapshot.Builder();
            builder.setId(id);
            builder.setTopActivityComponent(activityComponent);
            builder.setSystemUiVisibility(systemUiVisibility);
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

            ActivityManager.TaskSnapshot snapshot = builder.build();
            assertEquals(id, snapshot.getId());
            assertEquals(activityComponent, snapshot.getTopActivityComponent());
            assertEquals(systemUiVisibility, snapshot.getSystemUiVisibility());
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

    @Test
    public void testPrepareTaskSnapshot() {
        mAppWindow.mWinAnimator.mLastAlpha = 1f;
        spyOn(mAppWindow.mWinAnimator);
        doReturn(true).when(mAppWindow.mWinAnimator).getShown();
        doReturn(true).when(mAppWindow.mActivityRecord).isSurfaceShowing();

        final ActivityManager.TaskSnapshot.Builder builder =
                new ActivityManager.TaskSnapshot.Builder();
        mWm.mTaskSnapshotController.prepareTaskSnapshot(mAppWindow.mActivityRecord.getTask(),
                PixelFormat.UNKNOWN, builder);

        // The pixel format should be selected automatically.
        assertNotEquals(PixelFormat.UNKNOWN, builder.getPixelFormat());
    }
}
