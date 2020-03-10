/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.ActivityManager.TaskSnapshot;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

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
public class TaskSnapshotLowResDisabledTest extends TaskSnapshotPersisterTestBase {

    private static final Rect TEST_INSETS = new Rect(10, 20, 30, 40);

    private TaskSnapshotCache mCache;

    public TaskSnapshotLowResDisabledTest() {
        super(0.8f, 0.0f);
    }

    @Override
    @Before
    public void setUp() {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mCache = new TaskSnapshotCache(mWm, mLoader);
    }

    @Test
    public void testPersistAndLoadSnapshot() {
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        final File[] files = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/1.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/1.jpg")};
        final File[] nonExistsFiles = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/1_reduced.jpg")};
        assertTrueForFiles(files, File::exists, " must exist");
        assertTrueForFiles(nonExistsFiles, file -> !file.exists(), " must not exist");
        final TaskSnapshot snapshot = mLoader.loadTask(1, mTestUserId, false /* isLowResolution */);
        assertNotNull(snapshot);
        assertEquals(MOCK_SNAPSHOT_ID, snapshot.getId());
        assertEquals(TEST_INSETS, snapshot.getContentInsets());
        assertNotNull(snapshot.getSnapshot());
        assertEquals(Configuration.ORIENTATION_PORTRAIT, snapshot.getOrientation());
        assertNull(mLoader.loadTask(1, mTestUserId, true /* isLowResolution */));
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
                new File(FILES_DIR.getPath() + "/snapshots/1.jpg")};
        final File[] nonExistsFiles = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/1_reduced.jpg"),
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
                new File(FILES_DIR.getPath() + "/snapshots/2.proto"),
                new File(FILES_DIR.getPath() + "/snapshots/2.jpg")};
        final File[] nonExistsFiles = new File[]{
                new File(FILES_DIR.getPath() + "/snapshots/1_reduced.jpg"),
                new File(FILES_DIR.getPath() + "/snapshots/2_reduced.jpg")};
        assertTrueForFiles(existsFiles, File::exists, " must exist");
        assertTrueForFiles(nonExistsFiles, file -> !file.exists(), " must not exist");
    }

    @Test
    public void testReduced_notCached() {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mPersister.persistSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                false /* restoreFromDisk */, false /* isLowResolution */));

        // Attempt to load the low-res snapshot from the disk
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                true /* restoreFromDisk */, true /* isLowResolution */));

        // Load the high-res (default) snapshot from disk
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                true /* restoreFromDisk */, false /* isLowResolution */));

        // Make sure it's not in the cache now.
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                false /* restoreFromDisk */, true /* isLowResolution */));

        // Make sure it's not in the cache now.
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                false /* restoreFromDisk */, false /* isLowResolution */));
    }
}
