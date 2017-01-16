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
import static android.graphics.GraphicBuffer.USAGE_SW_READ_NEVER;
import static android.graphics.GraphicBuffer.USAGE_SW_READ_RARELY;
import static android.graphics.GraphicBuffer.USAGE_SW_WRITE_NEVER;
import static android.graphics.GraphicBuffer.create;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import android.app.ActivityManager.TaskSnapshot;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Debug;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
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
                false /* restoreFromDisk */));
        mCache.onAppRemoved(window.mAppToken);
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */));
    }

    @Test
    public void testAppDied() throws Exception {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */));
        mCache.onAppDied(window.mAppToken);

        // Should still be in the retrieval cache.
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */));

        // Trash retrieval cache.
        for (int i = 0; i < 20; i++) {
            mCache.putSnapshot(createWindow(null, FIRST_APPLICATION_WINDOW, "window").getTask(),
                    createSnapshot());
        }

        // Should not be in cache anymore
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */));
    }

    @Test
    public void testTaskRemoved() throws Exception {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */));
        mCache.onTaskRemoved(window.getTask().mTaskId);
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, 0 /* userId */,
                false /* restoreFromDisk */));
    }

    @Test
    public void testRestoreFromDisk() throws Exception {
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        mPersister.persistSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId, createSnapshot());
        mPersister.waitForQueueEmpty();
        assertNull(mCache.getSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId,
                false /* restoreFromDisk */));

        // Load it from disk
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId,
                true /* restoreFromDisk */));

        // Make sure it's in the cache now.
        assertNotNull(mCache.getSnapshot(window.getTask().mTaskId, sWm.mCurrentUserId,
                false /* restoreFromDisk */));
    }
}
