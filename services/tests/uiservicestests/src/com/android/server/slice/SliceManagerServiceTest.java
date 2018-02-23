/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.slice;

import static android.content.ContentProvider.maybeAddUserId;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.app.slice.ISliceListener;
import android.app.slice.SliceSpec;
import android.content.pm.PackageManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.server.LocalServices;
import com.android.server.UiServiceTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class SliceManagerServiceTest extends UiServiceTestCase {

    private static final String AUTH = "com.android.services.uitests";
    private static final Uri TEST_URI = maybeAddUserId(Uri.parse("content://" + AUTH + "/path"), 0);

    private static final SliceSpec[] EMPTY_SPECS = new SliceSpec[]{
    };

    private SliceManagerService mService;
    private PinnedSliceState mCreatedSliceState;
    private IBinder mToken = new Binder();

    @Before
    public void setup() {
        LocalServices.addService(PackageManagerInternal.class, mock(PackageManagerInternal.class));
        mContext.addMockSystemService(AppOpsManager.class, mock(AppOpsManager.class));
        mContext.getTestablePermissions().setPermission(TEST_URI, PERMISSION_GRANTED);

        mService = spy(new SliceManagerService(mContext, TestableLooper.get(this).getLooper()));
        mCreatedSliceState = mock(PinnedSliceState.class);
        doReturn(mCreatedSliceState).when(mService).createPinnedSlice(eq(TEST_URI));
    }

    @After
    public void teardown() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }

    @Test
    public void testAddPinCreatesPinned() throws RemoteException {
        doReturn("pkg").when(mService).getDefaultHome(anyInt());

        mService.pinSlice("pkg", TEST_URI, EMPTY_SPECS, mToken);
        mService.pinSlice("pkg", TEST_URI, EMPTY_SPECS, mToken);
        verify(mService, times(1)).createPinnedSlice(eq(TEST_URI));
    }

    @Test
    public void testRemovePinDestroysPinned() throws RemoteException {
        doReturn("pkg").when(mService).getDefaultHome(anyInt());

        mService.pinSlice("pkg", TEST_URI, EMPTY_SPECS, mToken);

        when(mCreatedSliceState.unpin(eq("pkg"), eq(mToken))).thenReturn(false);
        mService.unpinSlice("pkg", TEST_URI, mToken);
        verify(mCreatedSliceState, never()).destroy();
    }

}