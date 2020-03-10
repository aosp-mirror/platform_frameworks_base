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
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManager.TaskSnapshot;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.view.Surface;
import android.view.View;

import androidx.test.filters.MediumTest;

import com.android.server.wm.TaskSnapshotLoader.PreRLegacySnapshotConfig;
import com.android.server.wm.TaskSnapshotPersister.RemoveObsoleteFilesQueueItem;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;

import java.io.File;

/**
 * Test class for {@link TaskSnapshotPersister} and {@link TaskSnapshotLoader}
 *
 * Build/Install/Run:
 * atest TaskSnapshotPersisterLoaderTest
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskSnapshotPersisterLoaderTest extends TaskSnapshotPersisterTestBase {

    private static final float DELTA = 0.00001f;

    private static final Rect TEST_INSETS = new Rect(10, 20, 30, 40);

    public TaskSnapshotPersisterLoaderTest() {
        super(0.8f, 0.5f);
    }

    @Test
    public void testPersistAndLoadSnapshot() {
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        final File[] files = new File[]{new File(FILES_DIR.getPath() + "/snapshots/1.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/1.jpg"),
                new File(FILES_DIR.getPath() + "/snapshots/1_reduced.jpg")};
        assertTrueForFiles(files, File::exists, " must exist");
        final TaskSnapshot snapshot = mLoader.loadTask(1, mTestUserId, false /* isLowResolution */);
        assertNotNull(snapshot);
        assertEquals(MOCK_SNAPSHOT_ID, snapshot.getId());
        assertEquals(TEST_INSETS, snapshot.getContentInsets());
        assertNotNull(snapshot.getSnapshot());
        assertEquals(Configuration.ORIENTATION_PORTRAIT, snapshot.getOrientation());
    }

    @Test
    public void testTaskRemovedFromRecents() {
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.onTaskRemovedFromRecents(1, mTestUserId);
        mPersister.waitForQueueEmpty();
        assertFalse(new File(FILES_DIR.getPath() + "/snapshots/1.proto").exists());
        assertFalse(new File(FILES_DIR.getPath() + "/snapshots/1.jpg").exists());
        assertFalse(new File(FILES_DIR.getPath() + "/snapshots/1_reduced.jpg").exists());
    }

    /**
     * Tests that persisting a couple of snapshots is being throttled.
     */
    @Test
    public void testThrottling() {
        long ms = SystemClock.elapsedRealtime();
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(2, mTestUserId, createSnapshot());
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[]{mTestUserId});
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[]{mTestUserId});
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[]{mTestUserId});
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[]{mTestUserId});
        mPersister.waitForQueueEmpty();
        assertTrue(SystemClock.elapsedRealtime() - ms > 500);
    }

    /**
     * Tests that too many store write queue items are being purged.
     */
    @Test
    public void testPurging() {
        mPersister.persistSnapshot(100, mTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        mPersister.setPaused(true);
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[]{mTestUserId});
        mPersister.persistSnapshot(2, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(3, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(4, mTestUserId, createSnapshot());
        mPersister.setPaused(false);
        mPersister.waitForQueueEmpty();

        // Make sure 1,2 were purged but removeObsoleteFiles wasn't.
        final File[] existsFiles = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/3.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/4.proto")};
        final File[] nonExistsFiles = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/100.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/1.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/1.proto")};
        assertTrueForFiles(existsFiles, File::exists, " must exist");
        assertTrueForFiles(nonExistsFiles, file -> !file.exists(), " must not exist");
    }

    @Test
    public void testGetTaskId() {
        RemoveObsoleteFilesQueueItem removeObsoleteFilesQueueItem =
                mPersister.new RemoveObsoleteFilesQueueItem(new ArraySet<>(), new int[]{});
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("blablablulp"));
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("nothing.err"));
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("/invalid/"));
        assertEquals(12, removeObsoleteFilesQueueItem.getTaskId("12.jpg"));
        assertEquals(12, removeObsoleteFilesQueueItem.getTaskId("12.proto"));
        assertEquals(1, removeObsoleteFilesQueueItem.getTaskId("1.jpg"));
        assertEquals(1, removeObsoleteFilesQueueItem.getTaskId("1_reduced.jpg"));
    }

    @Test
    public void testLegacyPLowRamConfig() throws Exception {
        MockitoSession mockSession = mockitoSession()
                .initMocks(this)
                .mockStatic(ActivityManager.class)
                .startMocking();

        when(ActivityManager.isLowRamDeviceStatic()).thenReturn(true);

        // taskWidth and legacyScale as would be defined in the proto, and presence of a *.jpg file,
        // for any P low_ram device
        final int taskWidth = 0;
        final float legacyScale = 0f;
        final boolean hasHighResFile = false;

        PreRLegacySnapshotConfig highResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, false /* loadLowResolutionBitmap */);
        assertNotNull(highResConf);
        assertEquals(highResConf.mScale, 0.6f, DELTA);
        assertTrue(highResConf.mForceLoadReducedJpeg);

        PreRLegacySnapshotConfig lowResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, true /* loadLowResolutionBitmap */);
        assertNotNull(lowResConf);
        assertEquals(lowResConf.mScale, 0.6f, DELTA);
        assertTrue(lowResConf.mForceLoadReducedJpeg);

        mockSession.finishMocking();
    }

    @Test
    public void testLegacyPNonLowRamConfig() throws Exception {
        MockitoSession mockSession = mockitoSession()
                .initMocks(this)
                .mockStatic(ActivityManager.class)
                .startMocking();

        when(ActivityManager.isLowRamDeviceStatic()).thenReturn(false);

        // taskWidth and legacyScale as would be defined in the proto, and presence of a *.jpg file,
        // for any O device, or a P non-low_ram device
        final int taskWidth = 0;
        final float legacyScale = 0f;
        final boolean hasHighResFile = true;

        PreRLegacySnapshotConfig highResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, false /* loadLowResolutionBitmap */);
        assertNotNull(highResConf);
        assertEquals(highResConf.mScale, 1.0f, DELTA);
        assertFalse(highResConf.mForceLoadReducedJpeg);

        PreRLegacySnapshotConfig lowResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, true /* loadLowResolutionBitmap */);
        assertNotNull(lowResConf);
        assertEquals(lowResConf.mScale, 0.5f, DELTA);
        assertFalse(lowResConf.mForceLoadReducedJpeg);

        mockSession.finishMocking();
    }

    @Test
    public void testLegacyQLowRamConfig() throws Exception {
        MockitoSession mockSession = mockitoSession()
                .initMocks(this)
                .mockStatic(ActivityManager.class)
                .startMocking();

        when(ActivityManager.isLowRamDeviceStatic()).thenReturn(true);

        // taskWidth and legacyScale as would be defined in the proto, and presence of a *.jpg file,
        // for any Q low_ram device
        final int taskWidth = 0;
        final float legacyScale = 0.6f;
        final boolean hasHighResFile = false;

        PreRLegacySnapshotConfig highResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, false /* loadLowResolutionBitmap */);
        assertNotNull(highResConf);
        assertEquals(highResConf.mScale, legacyScale, DELTA);
        assertEquals(highResConf.mScale, 0.6f, DELTA);
        assertTrue(highResConf.mForceLoadReducedJpeg);

        PreRLegacySnapshotConfig lowResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, true /* loadLowResolutionBitmap */);
        assertNotNull(lowResConf);
        assertEquals(lowResConf.mScale, legacyScale, DELTA);
        assertEquals(lowResConf.mScale, 0.6f, DELTA);
        assertTrue(lowResConf.mForceLoadReducedJpeg);

        mockSession.finishMocking();
    }

    @Test
    public void testLegacyQNonLowRamConfig() throws Exception {
        MockitoSession mockSession = mockitoSession()
                .initMocks(this)
                .mockStatic(ActivityManager.class)
                .startMocking();

        when(ActivityManager.isLowRamDeviceStatic()).thenReturn(false);

        // taskWidth and legacyScale as would be defined in the proto, and presence of a *.jpg file,
        // for any Q non-low_ram device
        final int taskWidth = 0;
        final float legacyScale = 0.8f;
        final boolean hasHighResFile = true;

        PreRLegacySnapshotConfig highResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, false /* loadLowResolutionBitmap */);
        assertNotNull(highResConf);
        assertEquals(highResConf.mScale, legacyScale, DELTA);
        assertEquals(highResConf.mScale, 0.8f, DELTA);
        assertFalse(highResConf.mForceLoadReducedJpeg);

        PreRLegacySnapshotConfig lowResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, true /* loadLowResolutionBitmap */);
        assertNotNull(lowResConf);
        assertEquals(lowResConf.mScale, 0.5f * legacyScale, DELTA);
        assertEquals(lowResConf.mScale, 0.5f * 0.8f, DELTA);
        assertFalse(lowResConf.mForceLoadReducedJpeg);

        mockSession.finishMocking();
    }

    @Test
    public void testNonLegacyRConfig() throws Exception {
        // taskWidth and legacyScale as would be defined in the proto, and presence of a *.jpg file,
        // for any R device
        final int taskWidth = 1440;
        final float legacyScale = 0f;
        final boolean hasHighResFile = true;

        PreRLegacySnapshotConfig highResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, false /* loadLowResolutionBitmap */);
        assertNull(highResConf);

        PreRLegacySnapshotConfig lowResConf = mLoader.getLegacySnapshotConfig(
                taskWidth, legacyScale, hasHighResFile, true /* loadLowResolutionBitmap */);
        assertNull(lowResConf);
    }

    @Test
    public void testIsRealSnapshotPersistAndLoadSnapshot() {
        TaskSnapshot a = new TaskSnapshotBuilder()
                .setIsRealSnapshot(true)
                .build();
        TaskSnapshot b = new TaskSnapshotBuilder()
                .setIsRealSnapshot(false)
                .build();
        assertTrue(a.isRealSnapshot());
        assertFalse(b.isRealSnapshot());
        mPersister.persistSnapshot(1, mTestUserId, a);
        mPersister.persistSnapshot(2, mTestUserId, b);
        mPersister.waitForQueueEmpty();
        final TaskSnapshot snapshotA = mLoader.loadTask(1, mTestUserId,
                false /* isLowResolution */);
        final TaskSnapshot snapshotB = mLoader.loadTask(2, mTestUserId,
                false /* isLowResolution */);
        assertNotNull(snapshotA);
        assertNotNull(snapshotB);
        assertTrue(snapshotA.isRealSnapshot());
        assertFalse(snapshotB.isRealSnapshot());
    }

    @Test
    public void testWindowingModePersistAndLoadSnapshot() {
        TaskSnapshot a = new TaskSnapshotBuilder()
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .build();
        TaskSnapshot b = new TaskSnapshotBuilder()
                .setWindowingMode(WINDOWING_MODE_PINNED)
                .build();
        assertEquals(WINDOWING_MODE_FULLSCREEN, a.getWindowingMode());
        assertEquals(WINDOWING_MODE_PINNED, b.getWindowingMode());
        mPersister.persistSnapshot(1, mTestUserId, a);
        mPersister.persistSnapshot(2, mTestUserId, b);
        mPersister.waitForQueueEmpty();
        final TaskSnapshot snapshotA = mLoader.loadTask(1, mTestUserId,
                false /* isLowResolution */);
        final TaskSnapshot snapshotB = mLoader.loadTask(2, mTestUserId,
                false /* isLowResolution */);
        assertNotNull(snapshotA);
        assertNotNull(snapshotB);
        assertEquals(WINDOWING_MODE_FULLSCREEN, snapshotA.getWindowingMode());
        assertEquals(WINDOWING_MODE_PINNED, snapshotB.getWindowingMode());
    }

    @Test
    public void testIsTranslucentPersistAndLoadSnapshot() {
        TaskSnapshot a = new TaskSnapshotBuilder()
                .setIsTranslucent(true)
                .build();
        TaskSnapshot b = new TaskSnapshotBuilder()
                .setIsTranslucent(false)
                .build();
        assertTrue(a.isTranslucent());
        assertFalse(b.isTranslucent());
        mPersister.persistSnapshot(1, mTestUserId, a);
        mPersister.persistSnapshot(2, mTestUserId, b);
        mPersister.waitForQueueEmpty();
        final TaskSnapshot snapshotA = mLoader.loadTask(1, mTestUserId,
                false /* isLowResolution */);
        final TaskSnapshot snapshotB = mLoader.loadTask(2, mTestUserId,
                false /* isLowResolution */);
        assertNotNull(snapshotA);
        assertNotNull(snapshotB);
        assertTrue(snapshotA.isTranslucent());
        assertFalse(snapshotB.isTranslucent());
    }

    @Test
    public void testSystemUiVisibilityPersistAndLoadSnapshot() {
        final int lightBarFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        TaskSnapshot a = new TaskSnapshotBuilder()
                .setSystemUiVisibility(0)
                .build();
        TaskSnapshot b = new TaskSnapshotBuilder()
                .setSystemUiVisibility(lightBarFlags)
                .build();
        assertEquals(0, a.getSystemUiVisibility());
        assertEquals(lightBarFlags, b.getSystemUiVisibility());
        mPersister.persistSnapshot(1, mTestUserId, a);
        mPersister.persistSnapshot(2, mTestUserId, b);
        mPersister.waitForQueueEmpty();
        final TaskSnapshot snapshotA = mLoader.loadTask(1, mTestUserId,
                false /* isLowResolution */);
        final TaskSnapshot snapshotB = mLoader.loadTask(2, mTestUserId,
                false /* isLowResolution */);
        assertNotNull(snapshotA);
        assertNotNull(snapshotB);
        assertEquals(0, snapshotA.getSystemUiVisibility());
        assertEquals(lightBarFlags, snapshotB.getSystemUiVisibility());
    }

    @Test
    public void testScalePersistAndLoadSnapshot() {
        TaskSnapshot a = new TaskSnapshotBuilder()
                .setScaleFraction(0.25f)
                .build();
        TaskSnapshot b = new TaskSnapshotBuilder()
                .setScaleFraction(0.75f)
                .build();
        mPersister.persistSnapshot(1, mTestUserId, a);
        mPersister.persistSnapshot(2, mTestUserId, b);
        mPersister.waitForQueueEmpty();
        final TaskSnapshot snapshotA = mLoader.loadTask(1, mTestUserId,
                false /* isLowResolution */);
        final TaskSnapshot snapshotB = mLoader.loadTask(2, mTestUserId,
                false /* isLowResolution */);
        assertNotNull(snapshotA);
        assertNotNull(snapshotB);
    }

    @Test
    public void testRemoveObsoleteFiles() {
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(2, mTestUserId, createSnapshot());
        final ArraySet<Integer> taskIds = new ArraySet<>();
        taskIds.add(1);
        mPersister.removeObsoleteFiles(taskIds, new int[]{mTestUserId});
        mPersister.waitForQueueEmpty();
        final File[] existsFiles = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/1.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/1.jpg"),
                new File(FILES_DIR.getPath() + "/snapshots/1_reduced.jpg")};
        final File[] nonExistsFiles = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/2.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/2.jpg"),
                new File(FILES_DIR.getPath() + "/snapshots/2_reduced.jpg")};
        assertTrueForFiles(existsFiles, File::exists, " must exist");
        assertTrueForFiles(nonExistsFiles, file -> !file.exists(), " must not exist");
    }

    @Test
    public void testRemoveObsoleteFiles_addedOneInTheMeantime() {
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        final ArraySet<Integer> taskIds = new ArraySet<>();
        taskIds.add(1);
        mPersister.removeObsoleteFiles(taskIds, new int[]{mTestUserId});
        mPersister.persistSnapshot(2, mTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        final File[] existsFiles = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/1.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/1.jpg"),
                new File(FILES_DIR.getPath() + "/snapshots/1_reduced.jpg"),
                new File(FILES_DIR.getPath() + "/snapshots/2.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/2.jpg"),
                new File(FILES_DIR.getPath() + "/snapshots/2_reduced.jpg")};
        assertTrueForFiles(existsFiles, File::exists, " must exist");
    }

    @Test
    public void testRotationPersistAndLoadSnapshot() {
        TaskSnapshot a = new TaskSnapshotBuilder()
                .setRotation(Surface.ROTATION_270)
                .build();
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(2, mTestUserId, a);
        mPersister.waitForQueueEmpty();
        final TaskSnapshot snapshotA = mLoader.loadTask(1, mTestUserId,
                false /* isLowResolution */);
        final TaskSnapshot snapshotB = mLoader.loadTask(2, mTestUserId,
                false /* isLowResolution */);
        assertEquals(Surface.ROTATION_0, snapshotA.getRotation());
        assertEquals(Surface.ROTATION_270, snapshotB.getRotation());
    }
}
