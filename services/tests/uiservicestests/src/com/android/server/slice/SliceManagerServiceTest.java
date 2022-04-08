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
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.app.slice.SliceSpec;
import android.app.usage.UsageStatsManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

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
    private static final Uri TEST_URI = Uri.parse("content://" + AUTH + "/path");

    private static final SliceSpec[] EMPTY_SPECS = new SliceSpec[]{
    };

    private SliceManagerService mService;
    private PinnedSliceState mCreatedSliceState;
    private IBinder mToken = new Binder();
    private TestableContext mContextSpy;

    @Before
    public void setUp() {
        LocalServices.addService(UsageStatsManagerInternal.class,
                mock(UsageStatsManagerInternal.class));
        mContext.addMockSystemService(AppOpsManager.class, mock(AppOpsManager.class));
        mContext.getTestablePermissions().setPermission(TEST_URI, PERMISSION_GRANTED);

        mContextSpy = spy(mContext);
        mService = spy(new SliceManagerService(mContextSpy, TestableLooper.get(this).getLooper()));
        mCreatedSliceState = mock(PinnedSliceState.class);
        doReturn(mCreatedSliceState).when(mService).createPinnedSlice(eq(TEST_URI), anyString());
    }

    @After
    public void teardown() {
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
    }

    @Test
    public void testAddPinCreatesPinned() throws RemoteException {
        doReturn("pkg").when(mService).getDefaultHome(anyInt());

        mService.pinSlice("pkg", TEST_URI, EMPTY_SPECS, mToken);
        mService.pinSlice("pkg", TEST_URI, EMPTY_SPECS, mToken);
        verify(mService, times(1)).createPinnedSlice(eq(maybeAddUserId(TEST_URI, 0)), anyString());
    }

    @Test
    public void testRemovePinDestroysPinned() throws RemoteException {
        doReturn("pkg").when(mService).getDefaultHome(anyInt());

        mService.pinSlice("pkg", TEST_URI, EMPTY_SPECS, mToken);

        when(mCreatedSliceState.unpin(eq("pkg"), eq(mToken))).thenReturn(false);
        mService.unpinSlice("pkg", TEST_URI, mToken);
        verify(mCreatedSliceState, never()).destroy();
    }

    @Test
    public void testCheckAutoGrantPermissions() throws RemoteException {
        String[] testPerms = new String[] {
                "perm1",
                "perm2",
        };
        when(mContextSpy.checkUriPermission(any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(PERMISSION_DENIED);
        when(mContextSpy.checkPermission("perm1", Process.myPid(), Process.myUid()))
                .thenReturn(PERMISSION_DENIED);
        when(mContextSpy.checkPermission("perm2", Process.myPid(), Process.myUid()))
                .thenReturn(PERMISSION_GRANTED);
        mService.checkSlicePermission(TEST_URI, mContext.getPackageName(),
                mContext.getPackageName(), Process.myPid(),
                Process.myUid(), testPerms);

        verify(mContextSpy).checkPermission(eq("perm1"), eq(Process.myPid()), eq(Process.myUid()));
        verify(mContextSpy).checkPermission(eq("perm2"), eq(Process.myPid()), eq(Process.myUid()));
    }

    @Test(expected = IllegalStateException.class)
    public void testNoPinThrow() throws Exception {
        mService.getPinnedSpecs(TEST_URI, "pkg");
    }

    @Test
    public void testGetPinnedSpecs() throws Exception {
        SliceSpec[] specs = new SliceSpec[] {
            new SliceSpec("Something", 1) };
        mService.pinSlice("pkg", TEST_URI, specs, mToken);

        when(mCreatedSliceState.getSpecs()).thenReturn(specs);
        assertEquals(specs, mService.getPinnedSpecs(TEST_URI, "pkg"));
    }

}
