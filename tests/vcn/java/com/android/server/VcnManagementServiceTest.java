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

package com.android.server;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link VcnManagementService}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnManagementServiceTest {
    private final Context mMockContext = mock(Context.class);
    private final VcnManagementService.Dependencies mMockDeps =
            mock(VcnManagementService.Dependencies.class);
    private final TestLooper mTestLooper = new TestLooper();
    private final ConnectivityManager mConnMgr = mock(ConnectivityManager.class);
    private final VcnManagementService mVcnMgmtSvc;

    public VcnManagementServiceTest() {
        doReturn(Context.CONNECTIVITY_SERVICE)
                .when(mMockContext)
                .getSystemServiceName(ConnectivityManager.class);
        doReturn(mConnMgr).when(mMockContext).getSystemService(Context.CONNECTIVITY_SERVICE);

        doReturn(mTestLooper.getLooper()).when(mMockDeps).getLooper();
        mVcnMgmtSvc = new VcnManagementService(mMockContext, mMockDeps);
    }

    @Test
    public void testSystemReady() throws Exception {
        mVcnMgmtSvc.systemReady();

        verify(mConnMgr)
                .registerNetworkProvider(any(VcnManagementService.VcnNetworkProvider.class));
    }
}
