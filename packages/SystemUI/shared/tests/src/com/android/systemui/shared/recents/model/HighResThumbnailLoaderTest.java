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

package com.android.systemui.shared.recents.model;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.shared.SysuiSharedLibTestCase;
import com.android.systemui.shared.recents.model.Task.TaskKey;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * runtest --path frameworks/base/packages/SystemUI/shared/tests/src/com/android/systemui/shared/recents/model/HighResThumbnailLoaderTest.java
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HighResThumbnailLoaderTest extends SysuiSharedLibTestCase {

    private HighResThumbnailLoader mLoader;

    @Mock
    private ActivityManagerWrapper mMockActivityManagerWrapper;
    @Mock
    private Task mTask;

    private ThumbnailData mThumbnailData = new ThumbnailData();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLoader = new HighResThumbnailLoader(mMockActivityManagerWrapper, Looper.getMainLooper(),
                false /* reducedResolution */);
        mTask.key = new TaskKey(0, WINDOWING_MODE_UNDEFINED, null, null, 0, 0);
        when(mMockActivityManagerWrapper.getTaskThumbnail(anyInt(), anyBoolean()))
                .thenReturn(mThumbnailData);
        mLoader.setVisible(true);
        mLoader.setTaskLoadQueueIdle(true);
    }

    @Test
    public void testLoading() throws Exception {
        mLoader.setVisible(true);
        assertTrue(mLoader.isLoading());
        mLoader.setVisible(false);
        assertFalse(mLoader.isLoading());
        mLoader.setVisible(true);
        mLoader.setFlingingFast(true);
        assertFalse(mLoader.isLoading());
        mLoader.setFlingingFast(false);
        assertTrue(mLoader.isLoading());
        mLoader.setFlingingFast(false);
        mLoader.setTaskLoadQueueIdle(false);
        assertFalse(mLoader.isLoading());
        mLoader.setTaskLoadQueueIdle(true);
        assertTrue(mLoader.isLoading());
    }

    @Test
    public void testLoad() throws Exception {
        mLoader.onTaskVisible(mTask);
        mLoader.waitForLoaderIdle();
        waitForIdleSync();
        verify(mTask).notifyTaskDataLoaded(mThumbnailData, null);
    }

    @Test
    public void testFlinging_notLoaded() throws Exception {
        mLoader.setFlingingFast(true);
        mLoader.onTaskVisible(mTask);
        mLoader.waitForLoaderIdle();
        waitForIdleSync();
        verify(mTask, never()).notifyTaskDataLoaded(mThumbnailData, null);
    }

    /**
     * Tests whether task is loaded after stopping to fling
     */
    @Test
    public void testAfterFlinging() throws Exception {
        mLoader.setFlingingFast(true);
        mLoader.onTaskVisible(mTask);
        mLoader.setFlingingFast(false);
        mLoader.waitForLoaderIdle();
        waitForIdleSync();
        verify(mTask).notifyTaskDataLoaded(mThumbnailData, null);
    }

    @Test
    public void testAlreadyLoaded() throws Exception {
        mTask.thumbnail = new ThumbnailData();
        mTask.thumbnail.reducedResolution = false;
        mLoader.onTaskVisible(mTask);
        mLoader.waitForLoaderIdle();
        waitForIdleSync();
        verify(mTask, never()).notifyTaskDataLoaded(mThumbnailData, null);
    }
}
