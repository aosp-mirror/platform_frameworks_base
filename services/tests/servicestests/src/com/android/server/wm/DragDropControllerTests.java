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

import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.InputChannel;
import android.view.Surface;
import android.view.SurfaceSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
/**
 * Tests for the {@link DragDropController} class.
 *
 * atest com.android.server.wm.DragDropControllerTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public class DragDropControllerTests extends WindowTestsBase {
    private static final int TIMEOUT_MS = 1000;
    private DragDropController mTarget;
    private WindowState mWindow;
    private IBinder mToken;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        assertNotNull(sWm.mDragDropController);
        mTarget = sWm.mDragDropController;
        mWindow = createWindow(null, TYPE_BASE_APPLICATION, "window");
        synchronized (sWm.mWindowMap) {
            // Because sWm is a static object, the previous operation may remain.
            assertFalse(mTarget.dragDropActiveLocked());
        }
    }

    @After
    public void tearDown() {
        if (mToken != null) {
            mTarget.cancelDragAndDrop(mToken);
        }
    }

    @Test
    public void testPrepareDrag() throws Exception {
        final Surface surface = new Surface();
        mToken = mTarget.prepareDrag(
                new SurfaceSession(), 0, 0, mWindow.mClient, 0, 100, 100, surface);
        assertNotNull(mToken);
    }

    @Test
    public void testPrepareDrag_ZeroSizeSurface() throws Exception {
        final Surface surface = new Surface();
        mToken = mTarget.prepareDrag(
                new SurfaceSession(), 0, 0, mWindow.mClient, 0, 0, 0, surface);
        assertNull(mToken);
    }
}
