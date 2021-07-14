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

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.window.TaskSnapshot;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link TaskSnapshotCache}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskSnapshotCacheTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskSnapshotCacheTest extends TaskSnapshotPersisterTestBase {

    private TaskSnapshotCache mCache;
    @Mock
    TaskSnapshot mSnapshot;

    public TaskSnapshotCacheTest() {
        super(0.8f, 0.5f);
    }

    @Override
    @Before
    public void setUp() {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mCache = new TaskSnapshotCache(mWm, mLoader);
    }

    @Test
    public void testAppRemoved() {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* isLowResolution */));
        mCache.onAppRemoved(window.mActivityRecord);
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* isLowResolution */));
    }

    @Test
    public void testAppDied() {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* isLowResolution */));
        mCache.onAppDied(window.mActivityRecord);
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* isLowResolution */));
    }

    @Test
    public void testTaskRemoved() {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* isLowResolution */));
        mCache.onTaskRemoved(window.getTask().mTaskId);
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* isLowResolution */));
    }

    @Test
    public void testReduced_notCached() {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mPersister.persistSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                false /* restoreFromDisk */, false /* isLowResolution */));

        // Load it from disk
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                true /* restoreFromDisk */, true /* isLowResolution */));

        // Make sure it's not in the cache now.
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                false /* restoreFromDisk */, false /* isLowResolution */));
    }

    @Test
    public void testRestoreFromDisk() {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mPersister.persistSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                false /* restoreFromDisk */, false /* isLowResolution */));

        // Load it from disk
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, mWm.mCurrentUserId,
                true /* restoreFromDisk */, false /* isLowResolution */));
    }

    @Test
    public void testClearCache() {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), mSnapshot);
        assertEquals(mSnapshot, mCache.getSnapshot(window.getTask().mTaskId, 0, false, false));
        mCache.clearRunningCache();
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0, false, false));
    }
}
