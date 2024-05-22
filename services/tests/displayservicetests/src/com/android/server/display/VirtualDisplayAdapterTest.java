/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.os.IBinder;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VirtualDisplayAdapterTest {

    @Mock
    Context mContextMock;

    @Mock
    VirtualDisplayAdapter.SurfaceControlDisplayFactory mMockSufaceControlDisplayFactory;

    @Mock
    DisplayAdapter.Listener mMockListener;

    @Mock
    IVirtualDisplayCallback mMockCallback;

    @Mock
    IBinder mMockBinder;

    private TestHandler mHandler;

    private VirtualDisplayAdapter mVirtualDisplayAdapter;

    @Mock
    private DisplayManagerFlags mFeatureFlags;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mHandler = new TestHandler(null);
        mVirtualDisplayAdapter = new VirtualDisplayAdapter(new DisplayManagerService.SyncRoot(),
                mContextMock, mHandler, mMockListener, mMockSufaceControlDisplayFactory,
                mFeatureFlags);

        when(mMockCallback.asBinder()).thenReturn(mMockBinder);
    }

    @Test
    public void testCreatesVirtualDisplay() {
        VirtualDisplayConfig config = new VirtualDisplayConfig.Builder("test", /* width= */ 1,
                /* height= */ 1, /* densityDpi= */ 1).build();

        DisplayDevice result = mVirtualDisplayAdapter.createVirtualDisplayLocked(mMockCallback,
                /* projection= */ null, /* ownerUid= */ 10, /* packageName= */ "testpackage",
                /* uniqueId= */ "uniqueId", /* surface= */ null, /* flags= */ 0, config);

        assertNotNull(result);
    }

    @Test
    public void testCreatesVirtualDisplay_checkGeneratedDisplayUniqueIdPrefix() {
        VirtualDisplayConfig config = new VirtualDisplayConfig.Builder("test", /* width= */ 1,
                /* height= */ 1, /* densityDpi= */ 1).build();

        final String packageName = "testpackage";
        final String displayUniqueId = VirtualDisplayAdapter.generateDisplayUniqueId(
                packageName, Process.myUid(), config);

        DisplayDevice result = mVirtualDisplayAdapter.createVirtualDisplayLocked(
                mMockCallback, /* projection= */ null, /* ownerUid= */ 10,
                packageName, displayUniqueId, /* surface= */ null, /* flags= */ 0, config);

        assertNotNull(result);

        final String uniqueId = result.getUniqueId();
        assertTrue(uniqueId.startsWith(VirtualDisplayAdapter.UNIQUE_ID_PREFIX + packageName));
    }

    @Test
    public void testDoesNotCreateVirtualDisplayForSameCallback() {
        VirtualDisplayConfig config1 = new VirtualDisplayConfig.Builder("test", /* width= */ 1,
                /* height= */ 1, /* densityDpi= */ 1).build();
        VirtualDisplayConfig config2 = new VirtualDisplayConfig.Builder("test2", /* width= */ 1,
                /* height= */ 1, /* densityDpi= */ 1).build();
        mVirtualDisplayAdapter.createVirtualDisplayLocked(mMockCallback, /* projection= */ null,
                /* ownerUid= */ 10, /* packageName= */ "testpackage", /* uniqueId= */ "uniqueId1",
                /* surface= */ null, /* flags= */ 0, config1);

        DisplayDevice result = mVirtualDisplayAdapter.createVirtualDisplayLocked(mMockCallback,
                /* projection= */ null, /* ownerUid= */ 10, /* packageName= */ "testpackage",
                /* uniqueId= */ "uniqueId2", /* surface= */ null, /* flags= */ 0, config2);

        assertNull(result);
    }
}
