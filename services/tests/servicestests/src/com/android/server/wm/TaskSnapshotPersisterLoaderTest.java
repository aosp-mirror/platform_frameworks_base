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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager.TaskSnapshot;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;

import com.android.internal.util.Predicate;
import com.android.server.wm.TaskSnapshotPersister.RemoveObsoleteFilesQueueItem;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Test class for {@link TaskSnapshotPersister} and {@link TaskSnapshotLoader}
 *
 * runtest frameworks-services -c com.android.server.wm.TaskSnapshotPersisterLoaderTest
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskSnapshotPersisterLoaderTest extends TaskSnapshotPersisterTestBase {

    private static final Rect TEST_INSETS = new Rect(10, 20, 30, 40);

    @Test
    public void testPersistAndLoadSnapshot() {
        mPersister.persistSnapshot(1 , mTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        final File[] files = new File[] { new File(sFilesDir.getPath() + "/snapshots/1.proto"),
                new File(sFilesDir.getPath() + "/snapshots/1.jpg"),
                new File(sFilesDir.getPath() + "/snapshots/1_reduced.jpg")};
        assertTrueForFiles(files, File::exists, " must exist");
        final TaskSnapshot snapshot = mLoader.loadTask(1, mTestUserId, false /* reduced */);
        assertNotNull(snapshot);
        assertEquals(TEST_INSETS, snapshot.getContentInsets());
        assertNotNull(snapshot.getSnapshot());
        assertEquals(Configuration.ORIENTATION_PORTRAIT, snapshot.getOrientation());
    }

    private void assertTrueForFiles(File[] files, Predicate<File> predicate, String message) {
        for (File file : files) {
            assertTrue(file.getName() + message, predicate.apply(file));
        }
    }

    @Test
    public void testTaskRemovedFromRecents() {
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.onTaskRemovedFromRecents(1, mTestUserId);
        mPersister.waitForQueueEmpty();
        assertFalse(new File(sFilesDir.getPath() + "/snapshots/1.proto").exists());
        assertFalse(new File(sFilesDir.getPath() + "/snapshots/1.jpg").exists());
        assertFalse(new File(sFilesDir.getPath() + "/snapshots/1_reduced.jpg").exists());
    }

    /**
     * Tests that persisting a couple of snapshots is being throttled.
     */
    @Test
    public void testThrottling() {
        long ms = SystemClock.elapsedRealtime();
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(2, mTestUserId, createSnapshot());
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[] { mTestUserId });
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[] { mTestUserId });
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[] { mTestUserId });
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[] { mTestUserId });
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
        mPersister.removeObsoleteFiles(new ArraySet<>(), new int[] { mTestUserId });
        mPersister.persistSnapshot(2, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(3, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(4, mTestUserId, createSnapshot());
        mPersister.setPaused(false);
        mPersister.waitForQueueEmpty();

        // Make sure 1,2 were purged but removeObsoleteFiles wasn't.
        final File[] existsFiles = new File[] {
                new File(sFilesDir.getPath() + "/snapshots/3.proto"),
                new File(sFilesDir.getPath() + "/snapshots/4.proto")};
        final File[] nonExistsFiles = new File[] {
                new File(sFilesDir.getPath() + "/snapshots/100.proto"),
                new File(sFilesDir.getPath() + "/snapshots/1.proto"),
                new File(sFilesDir.getPath() + "/snapshots/1.proto")};
        assertTrueForFiles(existsFiles, File::exists, " must exist");
        assertTrueForFiles(nonExistsFiles, file -> !file.exists(), " must not exist");
    }

    @Test
    public void testGetTaskId() {
        RemoveObsoleteFilesQueueItem removeObsoleteFilesQueueItem =
                mPersister.new RemoveObsoleteFilesQueueItem(new ArraySet<>(), new int[] {});
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("blablablulp"));
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("nothing.err"));
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("/invalid/"));
        assertEquals(12, removeObsoleteFilesQueueItem.getTaskId("12.jpg"));
        assertEquals(12, removeObsoleteFilesQueueItem.getTaskId("12.proto"));
        assertEquals(1, removeObsoleteFilesQueueItem.getTaskId("1.jpg"));
        assertEquals(1, removeObsoleteFilesQueueItem.getTaskId("1_reduced.jpg"));
    }

    @Test
    public void testRemoveObsoleteFiles() {
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        mPersister.persistSnapshot(2, mTestUserId, createSnapshot());
        final ArraySet<Integer> taskIds = new ArraySet<>();
        taskIds.add(1);
        mPersister.removeObsoleteFiles(taskIds, new int[] { mTestUserId });
        mPersister.waitForQueueEmpty();
        final File[] existsFiles = new File[] {
                new File(sFilesDir.getPath() + "/snapshots/1.proto"),
                new File(sFilesDir.getPath() + "/snapshots/1.jpg"),
                new File(sFilesDir.getPath() + "/snapshots/1_reduced.jpg") };
        final File[] nonExistsFiles = new File[] {
                new File(sFilesDir.getPath() + "/snapshots/2.proto"),
                new File(sFilesDir.getPath() + "/snapshots/2.jpg"),
                new File(sFilesDir.getPath() + "/snapshots/2_reduced.jpg")};
        assertTrueForFiles(existsFiles, File::exists, " must exist");
        assertTrueForFiles(nonExistsFiles, file -> !file.exists(), " must not exist");
    }

    @Test
    public void testRemoveObsoleteFiles_addedOneInTheMeantime() {
        mPersister.persistSnapshot(1, mTestUserId, createSnapshot());
        final ArraySet<Integer> taskIds = new ArraySet<>();
        taskIds.add(1);
        mPersister.removeObsoleteFiles(taskIds, new int[] { mTestUserId });
        mPersister.persistSnapshot(2, mTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        final File[] existsFiles = new File[] {
                new File(sFilesDir.getPath() + "/snapshots/1.proto"),
                new File(sFilesDir.getPath() + "/snapshots/1.jpg"),
                new File(sFilesDir.getPath() + "/snapshots/1_reduced.jpg"),
                new File(sFilesDir.getPath() + "/snapshots/2.proto"),
                new File(sFilesDir.getPath() + "/snapshots/2.jpg"),
                new File(sFilesDir.getPath() + "/snapshots/2_reduced.jpg")};
        assertTrueForFiles(existsFiles, File::exists, " must exist");
    }
}
