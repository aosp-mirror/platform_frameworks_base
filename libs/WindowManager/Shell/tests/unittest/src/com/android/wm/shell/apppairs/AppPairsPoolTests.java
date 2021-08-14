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

package com.android.wm.shell.apppairs;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link AppPairsPool} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppPairsPoolTests extends ShellTestCase {
    private TestAppPairsController mController;
    private TestAppPairsPool mPool;
    @Mock private SyncTransactionQueue mSyncQueue;
    @Mock private ShellTaskOrganizer mTaskOrganizer;
    @Mock private DisplayController mDisplayController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDisplayController.getDisplayContext(anyInt())).thenReturn(mContext);
        mController = new TestAppPairsController(
                mTaskOrganizer,
                mSyncQueue,
                mDisplayController);
        mPool = mController.getPool();
    }

    @After
    public void tearDown() {}

    @Test
    public void testInitialState() {
        // Pool should always start off with at least 1 entry.
        assertThat(mPool.poolSize()).isGreaterThan(0);
    }

    @Test
    public void testAcquireRelease() {
        assertThat(mPool.poolSize()).isGreaterThan(0);
        final AppPair appPair = mPool.acquire();
        assertThat(mPool.poolSize()).isGreaterThan(0);
        mPool.release(appPair);
        assertThat(mPool.poolSize()).isGreaterThan(1);
    }
}
