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
import static android.graphics.GraphicBuffer.USAGE_SW_WRITE_NEVER;
import static android.graphics.GraphicBuffer.create;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import android.app.ActivityManager.TaskSnapshot;
import android.content.res.Configuration;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

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
public class TaskSnapshotCacheTest extends WindowTestsBase {

    @Test
    public void testCleanCache() throws Exception {
        TaskSnapshotCache snapshotCache = new TaskSnapshotCache();
        final WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, "window");
        snapshotCache.putSnapshot(window.getTask(), createSnapshot());
        assertNotNull(snapshotCache.getSnapshot(window.getTask()));
        snapshotCache.cleanCache(window.mAppToken);
        assertNull(snapshotCache.getSnapshot(window.getTask()));
    }

    private TaskSnapshot createSnapshot() {
        GraphicBuffer buffer = create(1, 1, PixelFormat.RGBA_8888,
                USAGE_HW_TEXTURE | USAGE_SW_WRITE_NEVER | USAGE_SW_READ_NEVER);
        return new TaskSnapshot(buffer, Configuration.ORIENTATION_PORTRAIT, new Rect());
    }
}
