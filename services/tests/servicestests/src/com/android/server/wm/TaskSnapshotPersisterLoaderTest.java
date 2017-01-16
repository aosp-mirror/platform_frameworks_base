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

import static android.graphics.GraphicBuffer.USAGE_HW_TEXTURE;
import static android.graphics.GraphicBuffer.USAGE_SW_READ_RARELY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager.TaskSnapshot;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;

import com.android.internal.util.Predicate;
import com.android.server.wm.TaskSnapshotPersister.RemoveObsoleteFilesQueueItem;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
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
public class TaskSnapshotPersisterLoaderTest {

    private static final String TEST_USER_NAME = "TaskSnapshotPersisterTest User";
    private static final Rect TEST_INSETS = new Rect(10, 20, 30, 40);

    private TaskSnapshotPersister mPersister;
    private TaskSnapshotLoader mLoader;
    private static int sTestUserId;
    private static File sFilesDir;
    private static UserManager sUserManager;

    @BeforeClass
    public static void setUpUser() {
        sUserManager = UserManager.get(InstrumentationRegistry.getContext());
        sTestUserId = createUser(TEST_USER_NAME, 0);
        sFilesDir = InstrumentationRegistry.getContext().getFilesDir();
    }

    @AfterClass
    public static void tearDownUser() {
        removeUser(sTestUserId);
    }

    @Before
    public void setUp() throws Exception {
        mPersister = new TaskSnapshotPersister(
                userId -> sFilesDir);
        mLoader = new TaskSnapshotLoader(mPersister);
        mPersister.start();
    }

    @After
    public void tearDown() throws Exception {
        cleanDirectory();
    }

    @Test
    public void testPersistAndLoadSnapshot() {
        mPersister.persistSnapshot(1 , sTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        final File[] files = new File[] { new File(sFilesDir.getPath() + "/snapshots/1.proto"),
                new File(sFilesDir.getPath() + "/snapshots/1.png") };
        assertTrueForFiles(files, File::exists, " must exist");
        final TaskSnapshot snapshot = mLoader.loadTask(1, sTestUserId);
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
        mPersister.persistSnapshot(1, sTestUserId, createSnapshot());
        mPersister.onTaskRemovedFromRecents(1, sTestUserId);
        mPersister.waitForQueueEmpty();
        assertFalse(new File(sFilesDir.getPath() + "/snapshots/1.proto").exists());
        assertFalse(new File(sFilesDir.getPath() + "/snapshots/1.png").exists());
    }

    /**
     * Tests that persisting a couple of snapshots is being throttled.
     */
    @Test
    public void testThrottling() {
        long ms = SystemClock.elapsedRealtime();
        mPersister.persistSnapshot(1, sTestUserId, createSnapshot());
        mPersister.persistSnapshot(2, sTestUserId, createSnapshot());
        mPersister.persistSnapshot(3, sTestUserId, createSnapshot());
        mPersister.persistSnapshot(4, sTestUserId, createSnapshot());
        mPersister.persistSnapshot(5, sTestUserId, createSnapshot());
        mPersister.persistSnapshot(6, sTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        assertTrue(SystemClock.elapsedRealtime() - ms > 500);
    }

    @Test
    public void testGetTaskId() {
        RemoveObsoleteFilesQueueItem removeObsoleteFilesQueueItem =
                mPersister.new RemoveObsoleteFilesQueueItem(new ArraySet<>(), new int[] {});
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("blablablulp"));
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("nothing.err"));
        assertEquals(-1, removeObsoleteFilesQueueItem.getTaskId("/invalid/"));
        assertEquals(12, removeObsoleteFilesQueueItem.getTaskId("12.png"));
        assertEquals(12, removeObsoleteFilesQueueItem.getTaskId("12.proto"));
        assertEquals(1, removeObsoleteFilesQueueItem.getTaskId("1.png"));
    }

    @Test
    public void testRemoveObsoleteFiles() {
        mPersister.persistSnapshot(1, sTestUserId, createSnapshot());
        mPersister.persistSnapshot(2, sTestUserId, createSnapshot());
        final ArraySet<Integer> taskIds = new ArraySet<>();
        taskIds.add(1);
        mPersister.removeObsoleteFiles(taskIds, new int[] { sTestUserId });
        mPersister.waitForQueueEmpty();
        final File[] existsFiles = new File[] {
                new File(sFilesDir.getPath() + "/snapshots/1.proto"),
                new File(sFilesDir.getPath() + "/snapshots/1.png") };
        final File[] nonExistsFiles = new File[] {
                new File(sFilesDir.getPath() + "/snapshots/2.proto"),
                new File(sFilesDir.getPath() + "/snapshots/2.png") };
        assertTrueForFiles(existsFiles, File::exists, " must exist");
        assertTrueForFiles(nonExistsFiles, file -> !file.exists(), " must not exist");
    }

    @Test
    public void testRemoveObsoleteFiles_addedOneInTheMeantime() {
        mPersister.persistSnapshot(1, sTestUserId, createSnapshot());
        final ArraySet<Integer> taskIds = new ArraySet<>();
        taskIds.add(1);
        mPersister.removeObsoleteFiles(taskIds, new int[] { sTestUserId });
        mPersister.persistSnapshot(2, sTestUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        final File[] existsFiles = new File[] {
                new File(sFilesDir.getPath() + "/snapshots/1.proto"),
                new File(sFilesDir.getPath() + "/snapshots/1.png"),
                new File(sFilesDir.getPath() + "/snapshots/2.proto"),
                new File(sFilesDir.getPath() + "/snapshots/2.png") };
        assertTrueForFiles(existsFiles, File::exists, " must exist");
    }

    private TaskSnapshot createSnapshot() {
        GraphicBuffer buffer = GraphicBuffer.create(100, 100, PixelFormat.RGBA_8888,
                USAGE_HW_TEXTURE | USAGE_SW_READ_RARELY | USAGE_SW_READ_RARELY);
        Canvas c = buffer.lockCanvas();
        c.drawColor(Color.RED);
        buffer.unlockCanvasAndPost(c);
        return new TaskSnapshot(buffer, Configuration.ORIENTATION_PORTRAIT, TEST_INSETS);
    }

    private static int createUser(String name, int flags) {
        UserInfo user = sUserManager.createUser(name, flags);
        if (user == null) {
            Assert.fail("Error while creating the test user: " + TEST_USER_NAME);
        }
        return user.id;
    }

    private static void removeUser(int userId) {
        if (!sUserManager.removeUser(userId)) {
            Assert.fail("Error while removing the test user: " + TEST_USER_NAME);
        }
    }

    private void cleanDirectory() {
        for (File file : new File(sFilesDir, "snapshots").listFiles()) {
            if (!file.isDirectory()) {
                file.delete();
            }
        }
    }
}
