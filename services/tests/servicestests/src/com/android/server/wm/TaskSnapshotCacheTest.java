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

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link TaskSnapshotCache}.
 *
 * runtest frameworks-services -c com.android.server.wm.TaskSnapshotCacheTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskSnapshotCacheTest extends TaskSnapshotPersisterTestBase {

    private TaskSnapshotCache mCache;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mCache = new TaskSnapshotCache(sWm, mLoader);
    }

    @Test
    public void testAppRemoved() throws Exception {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* reducedResolution */));
        mCache.onAppRemoved(window.mAppToken);
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* reducedResolution */));
    }

    @Test
    public void testAppDied() throws Exception {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* reducedResolution */));
        mCache.onAppDied(window.mAppToken);
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* reducedResolution */));
    }

    @Test
    public void testTaskRemoved() throws Exception {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* reducedResolution */));
        mCache.onTaskRemoved(window.getTask().mTaskId);
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */, false /* reducedResolution */));
    }

    @Test
    public void testReduced_notCached() throws Exception {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mPersister.persistSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId,
                false /* restoreFromDisk */, false /* reducedResolution */));

        // Load it from disk
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId,
                true /* restoreFromDisk */, true /* reducedResolution */));

        // Make sure it's not in the cache now.
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId,
                false /* restoreFromDisk */, false /* reducedResolution */));
    }

    @Test
    public void testRestoreFromDisk() throws Exception {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mPersister.persistSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId,
                false /* restoreFromDisk */, false /* reducedResolution */));

        // Load it from disk
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId,
                true /* restoreFromDisk */, false /* reducedResolution */));
    }
}
